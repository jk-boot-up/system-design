package app

import (
	"context"
	"time"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/domain"
)

// SendResult says what happened to one job.
type SendResult string

const (
	Sent      SendResult = "sent"
	Duplicate SendResult = "duplicate" // the email for this event already went out
	Busy      SendResult = "busy"      // another worker is sending it right now, or the sent log is unreachable
	Retried   SendResult = "retried"
	Dead      SendResult = "dead"
)

// ClaimLease bounds how long a crashed worker can block a job: after it, another worker may send.
const ClaimLease = 2 * time.Minute

// Worker sends one job. It returns an error only when the job could not be handed on (the caller then requeues it);
// otherwise the caller acknowledges it.
type Worker struct {
	Mailer    Mailer
	SentLog   SentLog
	Scheduler Scheduler
}

func (w Worker) Handle(ctx context.Context, job domain.EmailJob) (SendResult, error) {
	sent, err := w.SentLog.Sent(ctx, job.EventID)
	if err != nil {
		// Without the sent log the worker cannot tell a duplicate from a first try: wait rather than risk a second email.
		return Busy, w.Scheduler.RetryLater(ctx, job, 0)
	}
	if sent {
		return Duplicate, nil
	}
	claimed, err := w.SentLog.Claim(ctx, job.EventID, ClaimLease)
	if err != nil || !claimed {
		return Busy, w.Scheduler.RetryLater(ctx, job, 0)
	}
	if err := w.Mailer.Send(ctx, job); err != nil {
		_ = w.SentLog.Release(ctx, job.EventID)
		outcome, delay := domain.AfterFailure(job.Attempt, IsPermanent(err))
		if outcome == domain.Dead {
			return Dead, w.Scheduler.Dead(ctx, job, err.Error())
		}
		next := job
		next.Attempt++
		return Retried, w.Scheduler.RetryLater(ctx, next, delay)
	}
	// If this write fails the claim still expires and a duplicate delivery could send again: at least once, never zero.
	_ = w.SentLog.MarkSent(ctx, job.EventID)
	return Sent, nil
}
