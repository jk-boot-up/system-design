// Package dedupe keeps the sent log in Redis.
package dedupe

import (
	"context"
	"time"

	"github.com/redis/go-redis/v9"
)

// SentTTL is how long an event is remembered as sent; Kafka keeps events for 7 days, so no duplicate can be older.
const SentTTL = 7 * 24 * time.Hour

type RedisSentLog struct{ R *redis.Client }

func sentKey(id string) string  { return "notif:sent:" + id }
func claimKey(id string) string { return "notif:claim:" + id }

func (l RedisSentLog) Sent(ctx context.Context, id string) (bool, error) {
	n, err := l.R.Exists(ctx, sentKey(id)).Result()
	return n == 1, err
}

func (l RedisSentLog) Claim(ctx context.Context, id string, lease time.Duration) (bool, error) {
	return l.R.SetNX(ctx, claimKey(id), "1", lease).Result()
}

func (l RedisSentLog) MarkSent(ctx context.Context, id string) error {
	return l.R.Set(ctx, sentKey(id), "1", SentTTL).Err()
}

func (l RedisSentLog) Release(ctx context.Context, id string) error {
	return l.R.Del(ctx, claimKey(id)).Err()
}
