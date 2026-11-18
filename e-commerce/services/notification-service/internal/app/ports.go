// Package app holds the two use cases, routing events to jobs and sending jobs, written against ports.
package app

import (
	"context"
	"errors"
	"time"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/domain"
)

// JobQueue accepts email jobs durably: Publish returns only after the broker has confirmed the message.
type JobQueue interface {
	Publish(ctx context.Context, job domain.EmailJob) error
}

// DeadEvents keeps Kafka records that can never become an email.
type DeadEvents interface {
	DeadEvent(ctx context.Context, value []byte, reason string) error
}

// Scheduler moves a job that cannot be sent now: to a delay queue, or to the dead queue.
type Scheduler interface {
	RetryLater(ctx context.Context, job domain.EmailJob, delay int) error
	Dead(ctx context.Context, job domain.EmailJob, reason string) error
}

// Mailer sends one email. A *PermanentError means retrying cannot help.
type Mailer interface {
	Send(ctx context.Context, job domain.EmailJob) error
}

// PermanentError is a failure the mail server will repeat (unknown mailbox, rejected content).
type PermanentError struct{ Err error }

func (e *PermanentError) Error() string { return "permanent: " + e.Err.Error() }
func (e *PermanentError) Unwrap() error { return e.Err }

// IsPermanent reports whether err is a PermanentError.
func IsPermanent(err error) bool {
	var p *PermanentError
	return errors.As(err, &p)
}

// SentLog remembers which events already produced an email.
//   - Sent: the email for this event went out (kept for 7 days).
//   - Claim: a short lease so two workers holding duplicates of one job do not send at the same time.
type SentLog interface {
	Sent(ctx context.Context, eventID string) (bool, error)
	Claim(ctx context.Context, eventID string, lease time.Duration) (bool, error)
	MarkSent(ctx context.Context, eventID string) error
	Release(ctx context.Context, eventID string) error
}
