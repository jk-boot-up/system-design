package app

import (
	"context"
	"errors"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/domain"
)

// RouteResult says what happened to one Kafka record.
type RouteResult string

const (
	Queued     RouteResult = "queued"
	Ignored    RouteResult = "ignored"
	DeadLetter RouteResult = "dead_letter"
)

// Router turns order events into email jobs. The Kafka offset is committed only after Route returns without error, so
// a job is never lost; it may be queued twice, which the worker absorbs.
type Router struct {
	Queue JobQueue
	Dead  DeadEvents
}

func (r Router) Route(ctx context.Context, value []byte) (RouteResult, error) {
	job, err := domain.ParseEvent(value)
	if errors.Is(err, domain.ErrBadEvent) {
		return DeadLetter, r.Dead.DeadEvent(ctx, value, err.Error())
	}
	if err != nil {
		return "", err
	}
	if job == nil {
		return Ignored, nil
	}
	return Queued, r.Queue.Publish(ctx, *job)
}
