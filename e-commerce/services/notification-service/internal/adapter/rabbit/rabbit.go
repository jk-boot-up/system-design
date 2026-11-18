// Package rabbit holds the RabbitMQ side: the topology, a confirmed publisher, and the worker's consumer.
package rabbit

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/app"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/domain"
)

const (
	ExchangeEmail = "email"       // direct; routing key "send"
	ExchangeRetry = "email.retry" // direct; routing key = delay queue name
	ExchangeDead  = "email.dlx"   // direct; routing key "dead"
	QueueSend     = "email.send"
	QueueDead     = "email.dead"
)

// RetryQueueName names a delay queue after its delay, e.g. email.retry.30s. Queue arguments cannot change once
// declared, so a different delay is a different queue.
func RetryQueueName(d time.Duration) string { return "email.retry." + d.String() }

// Broker is one connection with one publishing channel (publisher confirms on).
type Broker struct {
	conn   *amqp.Connection
	pub    *amqp.Channel
	mu     sync.Mutex
	delays []time.Duration
}

func Dial(url string, delays []time.Duration) (*Broker, error) {
	conn, err := amqp.DialConfig(url, amqp.Config{Heartbeat: 10 * time.Second, Properties: amqp.Table{"connection_name": "notification-service"}})
	if err != nil {
		return nil, err
	}
	ch, err := conn.Channel()
	if err != nil {
		conn.Close()
		return nil, err
	}
	b := &Broker{conn: conn, pub: ch, delays: delays}
	if err := b.declare(); err != nil {
		conn.Close()
		return nil, fmt.Errorf("declare topology: %w", err)
	}
	if err := ch.Confirm(false); err != nil {
		conn.Close()
		return nil, err
	}
	return b, nil
}

// Closed is signalled when the connection drops; the process then exits and is restarted (jobs are safe in the queues).
func (b *Broker) Closed() <-chan *amqp.Error { return b.conn.NotifyClose(make(chan *amqp.Error, 1)) }

func (b *Broker) Close() error { return b.conn.Close() }

func quorum(extra amqp.Table) amqp.Table {
	t := amqp.Table{"x-queue-type": "quorum"}
	for k, v := range extra {
		t[k] = v
	}
	return t
}

func (b *Broker) declare() error {
	ch := b.pub
	for _, x := range []string{ExchangeEmail, ExchangeRetry, ExchangeDead} {
		if err := ch.ExchangeDeclare(x, amqp.ExchangeDirect, true, false, false, false, nil); err != nil {
			return err
		}
	}
	// Quorum queues are replicated with Raft; since RabbitMQ 4.0 they deliver priority 5 and above before the rest.
	if _, err := ch.QueueDeclare(QueueSend, true, false, false, false, quorum(amqp.Table{"x-delivery-limit": int32(20)})); err != nil {
		return err
	}
	if err := ch.QueueBind(QueueSend, "send", ExchangeEmail, false, nil); err != nil {
		return err
	}
	for _, d := range b.delays {
		name := RetryQueueName(d)
		// Nobody consumes a delay queue: messages wait out the TTL, then are dead-lettered back to email.send.
		// at-least-once dead-lettering (with reject-publish) means a message is never dropped on the way back.
		args := quorum(amqp.Table{"x-message-ttl": d.Milliseconds(), "x-dead-letter-exchange": ExchangeEmail,
			"x-dead-letter-routing-key": "send", "x-dead-letter-strategy": "at-least-once", "x-overflow": "reject-publish"})
		if _, err := ch.QueueDeclare(name, true, false, false, false, args); err != nil {
			return err
		}
		if err := ch.QueueBind(name, name, ExchangeRetry, false, nil); err != nil {
			return err
		}
	}
	if _, err := ch.QueueDeclare(QueueDead, true, false, false, false, quorum(nil)); err != nil {
		return err
	}
	return ch.QueueBind(QueueDead, "dead", ExchangeDead, false, nil)
}

// publish sends and waits for the broker's confirm: once it returns nil the message is on disk on a quorum of nodes.
func (b *Broker) publish(ctx context.Context, exchange, key string, job domain.EmailJob, headers amqp.Table) error {
	body, err := json.Marshal(job)
	if err != nil {
		return err
	}
	b.mu.Lock()
	dc, err := b.pub.PublishWithDeferredConfirmWithContext(ctx, exchange, key, true, false, amqp.Publishing{
		ContentType: "application/json", DeliveryMode: amqp.Persistent, Priority: job.Priority,
		MessageId: job.EventID, Timestamp: time.Now(), Headers: headers, Body: body,
	})
	b.mu.Unlock()
	if err != nil {
		return err
	}
	ok, err := dc.WaitContext(ctx)
	if err != nil {
		return err
	}
	if !ok {
		return fmt.Errorf("broker refused message %s for %s", job.EventID, exchange)
	}
	return nil
}

// Publish implements app.JobQueue.
func (b *Broker) Publish(ctx context.Context, job domain.EmailJob) error {
	return b.publish(ctx, ExchangeEmail, "send", job, nil)
}

// RetryLater implements app.Scheduler.
func (b *Broker) RetryLater(ctx context.Context, job domain.EmailJob, delay int) error {
	if delay >= len(b.delays) {
		delay = len(b.delays) - 1
	}
	return b.publish(ctx, ExchangeRetry, RetryQueueName(b.delays[delay]), job, amqp.Table{"x-attempt": int32(job.Attempt)})
}

// Dead implements app.Scheduler.
func (b *Broker) Dead(ctx context.Context, job domain.EmailJob, reason string) error {
	return b.publish(ctx, ExchangeDead, "dead", job, amqp.Table{"x-reason": reason, "x-attempt": int32(job.Attempt)})
}

// Consume runs the worker on email.send with manual acknowledgements until ctx ends. prefetch bounds the jobs in
// flight; that many goroutines handle them.
func (b *Broker) Consume(ctx context.Context, prefetch int, w app.Worker, observe func(app.SendResult, time.Duration)) error {
	ch, err := b.conn.Channel()
	if err != nil {
		return err
	}
	defer ch.Close()
	if err := ch.Qos(prefetch, 0, false); err != nil {
		return err
	}
	deliveries, err := ch.ConsumeWithContext(ctx, QueueSend, "notification-worker", false, false, false, false, nil)
	if err != nil {
		return err
	}
	var wg sync.WaitGroup
	for range prefetch {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for d := range deliveries {
				b.handle(ctx, d, w, observe)
			}
		}()
	}
	wg.Wait()
	return ctx.Err()
}

func (b *Broker) handle(ctx context.Context, d amqp.Delivery, w app.Worker, observe func(app.SendResult, time.Duration)) {
	start := time.Now()
	var job domain.EmailJob
	if err := json.Unmarshal(d.Body, &job); err != nil || job.EventID == "" {
		// A job nobody can read is moved aside as it is, never retried.
		_ = b.publishRaw(ctx, d.Body, "unreadable job")
		_ = d.Ack(false)
		observe(app.Dead, time.Since(start))
		return
	}
	res, err := w.Handle(ctx, job)
	if err != nil {
		// The job could not be handed on (broker trouble): put it back; RabbitMQ redelivers it.
		slog.Warn("job not handed on, requeued", "eventId", job.EventID, "err", err)
		_ = d.Nack(false, true)
		return
	}
	_ = d.Ack(false)
	observe(res, time.Since(start))
}

func (b *Broker) publishRaw(ctx context.Context, body []byte, reason string) error {
	b.mu.Lock()
	dc, err := b.pub.PublishWithDeferredConfirmWithContext(ctx, ExchangeDead, "dead", true, false, amqp.Publishing{
		DeliveryMode: amqp.Persistent, Headers: amqp.Table{"x-reason": reason}, Body: body})
	b.mu.Unlock()
	if err != nil {
		return err
	}
	_, err = dc.WaitContext(ctx)
	return err
}

// Depth returns the number of ready messages in a queue (for tests and the readiness view).
func (b *Broker) Depth(name string) (int, error) {
	ch, err := b.conn.Channel()
	if err != nil {
		return 0, err
	}
	defer ch.Close()
	q, err := ch.QueueDeclarePassive(name, true, false, false, false, nil)
	return q.Messages, err
}

// ParseDelays reads "30s,2m,10m".
func ParseDelays(s string) ([]time.Duration, error) {
	var out []time.Duration
	start := 0
	for i := 0; i <= len(s); i++ {
		if i == len(s) || s[i] == ',' {
			d, err := time.ParseDuration(s[start:i])
			if err != nil || d <= 0 {
				return nil, fmt.Errorf("bad retry delay %q", s[start:i])
			}
			out = append(out, d)
			start = i + 1
		}
	}
	return out, nil
}
