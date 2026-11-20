// Package kafka reads order events and hands them to the router; unreadable events go to the dead-letter topic.
package kafka

import (
	"context"
	"log/slog"
	"strconv"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/telemetry"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/app"
)

const (
	Topic = "order.events"
	Group = "notification-service"
)

type Consumer struct {
	client *kgo.Client
}

func New(brokers []string) (*Consumer, error) {
	c, err := kgo.NewClient(kgo.SeedBrokers(brokers...), kgo.ConsumerGroup(Group), kgo.ConsumeTopics(Topic),
		kgo.DisableAutoCommit(), kgo.ConsumeResetOffset(kgo.NewOffset().AtStart()), kgo.BlockRebalanceOnPoll(),
		kgo.ClientID("notification-service"))
	if err != nil {
		return nil, err
	}
	return &Consumer{client: c}, nil
}

func (c *Consumer) Close() { c.client.Close() }

// sourceKey carries the record being routed, so DeadEvent can keep its key and headers.
type sourceKey struct{}

// DeadEvent implements app.DeadEvents: the record goes to order.events.dlq with its original key and headers (so a
// replay lands on the same partition and keeps its traceparent), plus the reason and where it came from.
func (c *Consumer) DeadEvent(ctx context.Context, value []byte, reason string) error {
	r := &kgo.Record{Topic: Topic + ".dlq", Value: value}
	if src, ok := ctx.Value(sourceKey{}).(*kgo.Record); ok {
		r.Key = src.Key
		r.Headers = append(r.Headers, src.Headers...)
		r.Headers = append(r.Headers,
			kgo.RecordHeader{Key: "x-original-topic", Value: []byte(src.Topic)},
			kgo.RecordHeader{Key: "x-original-partition", Value: []byte(strconv.Itoa(int(src.Partition)))},
			kgo.RecordHeader{Key: "x-original-offset", Value: []byte(strconv.FormatInt(src.Offset, 10))})
	}
	r.Headers = append(r.Headers,
		kgo.RecordHeader{Key: "x-original-group", Value: []byte(Group)},
		kgo.RecordHeader{Key: "x-reason", Value: []byte(reason)})
	return c.client.ProduceSync(ctx, r).FirstErr()
}

// Run routes records in order. A record that cannot be handed on (RabbitMQ down) is retried with back-off, holding its
// partition, and offsets are committed only after every record in the batch is queued.
func (c *Consumer) Run(ctx context.Context, router app.Router, observe func(app.RouteResult)) error {
	// Polling blocks rebalances until AllowRebalance; release on exit too, or Close (leaving the group) would wait forever.
	defer c.client.AllowRebalance()
	for {
		fetches := c.client.PollFetches(ctx)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		fetches.EachError(func(t string, p int32, err error) { slog.Warn("fetch error", "topic", t, "partition", p, "err", err) })
		var done []*kgo.Record
		fetches.EachRecord(func(r *kgo.Record) {
			// Continue the trace of the request that wrote the event (its traceparent header, from order-service's outbox).
			rctx, span := telemetry.Tracer().Start(otel.GetTextMapPropagator().Extract(ctx, telemetry.KafkaHeaders{R: r}),
				Topic+" process", trace.WithSpanKind(trace.SpanKindConsumer), trace.WithAttributes(
					attribute.String("messaging.system", "kafka"), attribute.String("messaging.destination.name", r.Topic),
					attribute.String("messaging.consumer.group.name", Group), attribute.Int("messaging.destination.partition.id", int(r.Partition)),
					attribute.Int64("messaging.kafka.offset", r.Offset)))
			defer span.End()
			rctx = context.WithValue(rctx, sourceKey{}, r)
			backoff := 200 * time.Millisecond
			for ctx.Err() == nil {
				res, err := router.Route(rctx, r.Value)
				if err == nil {
					span.SetAttributes(attribute.String("notification.route", string(res)))
					observe(res)
					done = append(done, r)
					return
				}
				span.RecordError(err)
				span.SetStatus(codes.Error, "event not queued")
				slog.WarnContext(rctx, "event not queued, retrying", "offset", r.Offset, "err", err)
				select {
				case <-ctx.Done():
				case <-time.After(backoff):
				}
				backoff = min(backoff*2, 30*time.Second)
			}
		})
		if len(done) > 0 {
			if err := c.client.CommitRecords(context.WithoutCancel(ctx), done...); err != nil {
				slog.Warn("commit failed; records will be routed again", "err", err)
			}
		}
		c.client.AllowRebalance()
	}
}
