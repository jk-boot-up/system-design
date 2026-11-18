package domain

import "time"

// MaxAttempts is how many times an email is tried before it is dead-lettered.
const MaxAttempts = 5

// RetryDelays are the delay queues: the n-th failure waits RetryDelays[min(n, len)-1].
var RetryDelays = []time.Duration{30 * time.Second, 2 * time.Minute, 10 * time.Minute}

// Outcome of a failed send.
type Outcome int

const (
	Retry Outcome = iota // try again after a delay
	Dead                 // give up: an operator looks at email.dead
)

// AfterFailure decides what happens to a job whose send just failed. Permanent failures (the mail server rejected the
// address or the message) are not retried; temporary ones are, until MaxAttempts.
func AfterFailure(attempt int, permanent bool) (Outcome, int) {
	if permanent || attempt >= MaxAttempts {
		return Dead, -1
	}
	i := attempt - 1
	if i >= len(RetryDelays) {
		i = len(RetryDelays) - 1
	}
	return Retry, i
}
