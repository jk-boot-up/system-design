package app

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/domain"
)

type fakes struct {
	mu       sync.Mutex
	queued   []domain.EmailJob
	deadEvt  [][]byte
	mailed   []domain.EmailJob
	mailErr  error
	sent     map[string]bool
	claims   map[string]bool
	logDown  bool
	retries  []domain.EmailJob
	delays   []int
	deadJobs []domain.EmailJob
}

func newFakes() *fakes { return &fakes{sent: map[string]bool{}, claims: map[string]bool{}} }

func (f *fakes) Publish(_ context.Context, j domain.EmailJob) error {
	f.queued = append(f.queued, j)
	return nil
}
func (f *fakes) DeadEvent(_ context.Context, v []byte, _ string) error {
	f.deadEvt = append(f.deadEvt, v)
	return nil
}
func (f *fakes) RetryLater(_ context.Context, j domain.EmailJob, d int) error {
	f.retries = append(f.retries, j)
	f.delays = append(f.delays, d)
	return nil
}
func (f *fakes) Dead(_ context.Context, j domain.EmailJob, _ string) error {
	f.deadJobs = append(f.deadJobs, j)
	return nil
}
func (f *fakes) Send(_ context.Context, j domain.EmailJob) error {
	if f.mailErr != nil {
		return f.mailErr
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.mailed = append(f.mailed, j)
	return nil
}
func (f *fakes) Sent(_ context.Context, id string) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.logDown {
		return false, errors.New("redis down")
	}
	return f.sent[id], nil
}
func (f *fakes) Claim(_ context.Context, id string, _ time.Duration) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.claims[id] {
		return false, nil
	}
	f.claims[id] = true
	return true, nil
}
func (f *fakes) MarkSent(_ context.Context, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.sent[id] = true
	return nil
}
func (f *fakes) Release(_ context.Context, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.claims, id)
	return nil
}

func worker(f *fakes) Worker { return Worker{Mailer: f, SentLog: f, Scheduler: f} }

var job = domain.EmailJob{EventID: "e1", To: "ada@example.com", Subject: "s", Body: "b", Priority: 8, Attempt: 1}

func TestRouterQueuesIgnoresAndDeadLetters(t *testing.T) {
	f := newFakes()
	r := Router{Queue: f, Dead: f}
	ctx := context.Background()
	res, _ := r.Route(ctx, []byte(`{"eventId":"e1","type":"order.OrderConfirmed","payload":{"orderId":"o","customerEmail":"a@b.c"}}`))
	res2, _ := r.Route(ctx, []byte(`{"eventId":"e2","type":"order.OrderPlaced","payload":{}}`))
	res3, _ := r.Route(ctx, []byte(`garbage`))
	if res != Queued || res2 != Ignored || res3 != DeadLetter || len(f.queued) != 1 || len(f.deadEvt) != 1 {
		t.Fatalf("%v %v %v queued=%d dead=%d", res, res2, res3, len(f.queued), len(f.deadEvt))
	}
}

func TestSameEventTwiceSendsOnce(t *testing.T) {
	f := newFakes()
	w := worker(f)
	r1, _ := w.Handle(context.Background(), job)
	r2, _ := w.Handle(context.Background(), job)
	if r1 != Sent || r2 != Duplicate || len(f.mailed) != 1 {
		t.Fatalf("%v %v mailed=%d", r1, r2, len(f.mailed))
	}
}

func TestConcurrentDuplicatesSendOnce(t *testing.T) {
	f := newFakes()
	w := worker(f)
	var wg sync.WaitGroup
	for range 20 {
		wg.Add(1)
		go func() { defer wg.Done(); _, _ = w.Handle(context.Background(), job) }()
	}
	wg.Wait()
	if len(f.mailed) != 1 {
		t.Fatalf("mailed %d times", len(f.mailed))
	}
}

func TestTemporaryFailureClimbsTheLadderThenDies(t *testing.T) {
	f := newFakes()
	f.mailErr = errors.New("421 try later")
	w := worker(f)
	j := job
	for attempt := 1; attempt <= domain.MaxAttempts; attempt++ {
		res, _ := w.Handle(context.Background(), j)
		if attempt < domain.MaxAttempts {
			if res != Retried {
				t.Fatalf("attempt %d: %v", attempt, res)
			}
			j = f.retries[len(f.retries)-1]
			if j.Attempt != attempt+1 {
				t.Fatalf("attempt not incremented: %d", j.Attempt)
			}
		} else if res != Dead {
			t.Fatalf("last attempt: %v", res)
		}
	}
	if got := f.delays; len(got) != 4 || got[0] != 0 || got[1] != 1 || got[2] != 2 || got[3] != 2 {
		t.Fatalf("delays %v", got)
	}
	if len(f.claims) != 0 {
		t.Fatalf("claim not released after failure")
	}
}

func TestPermanentFailureIsDeadAtOnce(t *testing.T) {
	f := newFakes()
	f.mailErr = &PermanentError{Err: errors.New("550 no such mailbox")}
	if res, _ := worker(f).Handle(context.Background(), job); res != Dead || len(f.retries) != 0 {
		t.Fatalf("%v retries=%d", res, len(f.retries))
	}
}

func TestSentLogDownMeansWaitNotSend(t *testing.T) {
	f := newFakes()
	f.logDown = true
	res, _ := worker(f).Handle(context.Background(), job)
	if res != Busy || len(f.mailed) != 0 || len(f.retries) != 1 || f.retries[0].Attempt != 1 {
		t.Fatalf("%v mailed=%d retries=%v", res, len(f.mailed), f.retries)
	}
}
