//go:build containers

// Real Kafka, RabbitMQ, Redis and Mailpit. Run with: go test -tags containers ./test/
package test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	tckafka "github.com/testcontainers/testcontainers-go/modules/kafka"
	tcrabbit "github.com/testcontainers/testcontainers-go/modules/rabbitmq"
	tcredis "github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/wait"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/dedupe"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/kafka"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/rabbit"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/smtp"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/app"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/domain"
)

// flaky fails sends to chosen recipients: "flaky-N@…" fails N times then succeeds, "never@…" always fails (temporary),
// "bounce@…" is rejected permanently.
type flaky struct {
	real  app.Mailer
	mu    sync.Mutex
	tries map[string]int
}

func (f *flaky) Send(ctx context.Context, job domain.EmailJob) error {
	f.mu.Lock()
	f.tries[job.To]++
	n := f.tries[job.To]
	f.mu.Unlock()
	var fails int
	switch {
	case strings.HasPrefix(job.To, "bounce@"):
		return &app.PermanentError{Err: errors.New("550 no such mailbox")}
	case strings.HasPrefix(job.To, "never@"):
		return errors.New("421 service not available")
	case strings.HasPrefix(job.To, "flaky-"):
		_, _ = fmt.Sscanf(job.To, "flaky-%d@", &fails)
		if n <= fails {
			return errors.New("451 try again later")
		}
	}
	return f.real.Send(ctx, job)
}

func event(id, typ, email string) []byte {
	return []byte(fmt.Sprintf(`{"eventId":%q,"type":%q,"version":1,"occurredAt":"2026-11-18T10:00:00Z","aggregateId":"o-%s",
	 "payload":{"orderId":"o-%s","customerId":"c-1","customerEmail":%q,"reason":"OUT_OF_STOCK",
	 "total":{"amount":"89.90","currency":"EUR"},
	 "lines":[{"sku":"SKU-1001","productName":"Trail Runner 2","quantity":1,"unitPrice":{"amount":"89.90","currency":"EUR"}}]}}`,
		id, typ, id, id, email))
}

type mailbox struct{ api string }

func (m mailbox) count(t *testing.T, to string) int {
	resp, err := http.Get(m.api + "/api/v1/search?query=" + "to:" + to)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var r struct {
		MessagesCount int `json:"messages_count"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&r)
	return r.MessagesCount
}

var diagnostics = func() string { return "" }

func eventually(t *testing.T, within time.Duration, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(within)
	for time.Now().Before(deadline) {
		if ok() {
			return
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for: %s (%s)", what, diagnostics())
}

func TestEventsBecomeEmailsOnceWithRetriesAndDeadLetters(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()

	kc, err := tckafka.Run(ctx, "confluentinc/confluent-local:8.1.0", tckafka.WithClusterID("notification-test")) // the module needs a Confluent image
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, kc)
	rc, err := tcrabbit.Run(ctx, "rabbitmq:4.2-management-alpine")
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, rc)
	redisC, err := tcredis.Run(ctx, "redis:8-alpine")
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, redisC)
	mp, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{Started: true,
		ContainerRequest: testcontainers.ContainerRequest{Image: "axllent/mailpit:v1.27", ExposedPorts: []string{"1025/tcp", "8025/tcp"},
			WaitingFor: wait.ForHTTP("/livez").WithPort("8025/tcp")}})
	if err != nil {
		t.Fatal(err)
	}
	testcontainers.CleanupContainer(t, mp)

	brokers, _ := kc.Brokers(ctx)
	amqpURL, _ := rc.AmqpURL(ctx)
	redisURL, _ := redisC.ConnectionString(ctx)
	smtpAddr, _ := mp.PortEndpoint(ctx, "1025/tcp", "")
	apiURL, _ := mp.PortEndpoint(ctx, "8025/tcp", "http")
	box := mailbox{api: apiURL}

	broker, err := rabbit.Dial(amqpURL, []time.Duration{time.Second, 2 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	defer broker.Close()
	ropts, _ := redis.ParseURL(redisURL)
	rdb := redis.NewClient(ropts)
	consumer, err := kafka.New(brokers)
	if err != nil {
		t.Fatal(err)
	}
	defer consumer.Close()

	var mu sync.Mutex
	results := map[string]int{}
	diagnostics = func() string {
		send, _ := broker.Depth(rabbit.QueueSend)
		dead, _ := broker.Depth(rabbit.QueueDead)
		resp, err := http.Get(apiURL + "/api/v1/messages")
		total := -1
		if err == nil {
			var r struct {
				Total int `json:"total"`
			}
			_ = json.NewDecoder(resp.Body).Decode(&r)
			resp.Body.Close()
			total = r.Total
		}
		mu.Lock()
		defer mu.Unlock()
		return fmt.Sprintf("email.send=%d email.dead=%d mailpit total=%d results=%v", send, dead, total, results)
	}
	mailer := &flaky{real: smtp.Mailer{Addr: smtpAddr, From: "Shop <orders@shop.example>", Timeout: 5 * time.Second}, tries: map[string]int{}}
	worker := app.Worker{Mailer: mailer, SentLog: dedupe.RedisSentLog{R: rdb}, Scheduler: broker}
	runCtx, stop := context.WithCancel(ctx)
	defer stop()
	go func() {
		_ = broker.Consume(runCtx, 20, worker, func(r app.SendResult, _ time.Duration) { mu.Lock(); results[string(r)]++; mu.Unlock() })
	}()

	producer, _ := kgo.NewClient(kgo.SeedBrokers(brokers...))
	defer producer.Close()
	// Topics are provisioned by the platform, as in production (brokers there do not auto-create topics).
	if _, err := kadm.NewClient(producer).CreateTopics(ctx, 3, 1, nil, kafka.Topic, kafka.Topic+".dlq"); err != nil {
		t.Fatal(err)
	}
	send := func(key string, value []byte) {
		if err := producer.ProduceSync(ctx, &kgo.Record{Topic: kafka.Topic, Key: []byte(key), Value: value}).FirstErr(); err != nil {
			t.Fatal(err)
		}
	}

	// The same OrderConfirmed delivered three times (an outbox relay re-sent it) and an event that sends nothing.
	for range 3 {
		send("o-1", event("evt-1", "order.OrderConfirmed", "ada@example.com"))
	}
	send("o-1", event("evt-1b", "order.OrderPlaced", "ada@example.com"))
	send("o-2", event("evt-2", "order.OrderCancelled", "bob@example.com"))
	send("o-3", event("evt-3", "order.OrderConfirmed", "flaky-2@example.com"))
	send("o-4", event("evt-4", "order.OrderConfirmed", "bounce@example.com"))
	send("o-5", event("evt-5", "order.OrderConfirmed", "never@example.com"))
	send("o-6", []byte("this is not json"))
	// Started after the topic exists: a group member only notices a new topic at its next metadata refresh.
	go func() { _ = consumer.Run(runCtx, app.Router{Queue: broker, Dead: consumer}, func(app.RouteResult) {}) }()

	eventually(t, 30*time.Second, "ada's and bob's emails", func() bool {
		return box.count(t, "ada@example.com") >= 1 && box.count(t, "bob@example.com") == 1
	})
	eventually(t, 30*time.Second, "flaky-2 delivered after two delayed retries", func() bool { return box.count(t, "flaky-2@example.com") == 1 })
	eventually(t, 60*time.Second, "bounce and never dead-lettered", func() bool {
		n, _ := broker.Depth(rabbit.QueueDead)
		return n == 2
	})
	time.Sleep(2 * time.Second) // let any stray duplicate arrive
	if n := box.count(t, "ada@example.com"); n != 1 {
		t.Fatalf("ada received %d emails, want exactly 1", n)
	}
	mailer.mu.Lock()
	tries := map[string]int{"flaky": mailer.tries["flaky-2@example.com"], "bounce": mailer.tries["bounce@example.com"], "never": mailer.tries["never@example.com"]}
	mailer.mu.Unlock()
	if tries["flaky"] != 3 || tries["bounce"] != 1 || tries["never"] != domain.MaxAttempts {
		t.Fatalf("send attempts %v", tries)
	}
	mu.Lock()
	if results["duplicate"] != 2 || results["dead"] != 2 {
		t.Errorf("results %v", results)
	}
	mu.Unlock()

	// The unreadable event is on the dead-letter topic, with its reason.
	dlq, _ := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ConsumeTopics(kafka.Topic+".dlq"), kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()))
	defer dlq.Close()
	pollCtx, pc := context.WithTimeout(ctx, 20*time.Second)
	defer pc()
	recs := dlq.PollFetches(pollCtx).Records()
	if len(recs) != 1 || string(recs[0].Value) != "this is not json" {
		t.Fatalf("dlq records %d", len(recs))
	}
	if !strings.Contains(string(recs[0].Headers[0].Value), "bad event") {
		t.Fatalf("reason header %q", recs[0].Headers[0].Value)
	}
}
