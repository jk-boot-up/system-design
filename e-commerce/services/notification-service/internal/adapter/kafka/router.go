// Package kafka reads order events and hands them to the router; unreadable events go to the dead-letter topic.
package kafka

import (
	"context"
	"log/slog"
	"time"

	"github.com/twmb/franz-go/pkg/kgo"

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

// DeadEvent implements app.DeadEvents: the record goes to order.events.dlq with the reason as a header.
func (c *Consumer) DeadEvent(ctx context.Context, value []byte, reason string) error {
	r := &kgo.Record{Topic: Topic + ".dlq", Value: value, Headers: []kgo.RecordHeader{{Key: "x-reason", Value: []byte(reason)}}}
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
			backoff := 200 * time.Millisecond
			for ctx.Err() == nil {
				res, err := router.Route(ctx, r.Value)
				if err == nil {
					observe(res)
					done = append(done, r)
					return
				}
				slog.Warn("event not queued, retrying", "offset", r.Offset, "err", err)
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
