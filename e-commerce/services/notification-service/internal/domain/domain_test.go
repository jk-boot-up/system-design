package domain

import (
	"errors"
	"strings"
	"testing"
)

const confirmed = `{"eventId":"0193a1b2-0000-7000-8000-000000000001","type":"order.OrderConfirmed","version":1,
 "occurredAt":"2026-11-01T10:15:31.204Z","aggregateId":"o-1",
 "payload":{"orderId":"o-1","customerId":"c-1","customerEmail":"ada@example.com","total":{"amount":"179.80","currency":"EUR"},
 "lines":[{"sku":"SKU-1001","productName":"Trail Runner 2","quantity":2,"unitPrice":{"amount":"89.90","currency":"EUR"}}]}}`

func TestConfirmedBecomesATransactionalEmail(t *testing.T) {
	job, err := ParseEvent([]byte(confirmed))
	if err != nil || job == nil {
		t.Fatalf("job=%v err=%v", job, err)
	}
	if job.To != "ada@example.com" || job.Priority != PriorityTransactional || job.Attempt != 1 ||
		job.EventID != "0193a1b2-0000-7000-8000-000000000001" {
		t.Fatalf("unexpected job %+v", job)
	}
	for _, want := range []string{"o-1", "2 × Trail Runner 2", "179.80 EUR"} {
		if !strings.Contains(job.Body, want) {
			t.Errorf("body misses %q:\n%s", want, job.Body)
		}
	}
}

func TestCancelledExplainsTheReason(t *testing.T) {
	job, err := ParseEvent([]byte(`{"eventId":"e2","type":"order.OrderCancelled","payload":{"orderId":"o-2",
		"customerEmail":"bob@example.com","reason":"PAYMENT_DECLINED","total":{"amount":"10.00","currency":"EUR"}}}`))
	if err != nil || !strings.Contains(job.Body, "payment was declined") || job.Kind != "order-cancelled" {
		t.Fatalf("job=%+v err=%v", job, err)
	}
}

func TestOtherEventsSendNothingAndBrokenOnesAreBad(t *testing.T) {
	if job, err := ParseEvent([]byte(`{"eventId":"e3","type":"order.OrderPlaced","payload":{}}`)); job != nil || err != nil {
		t.Fatalf("OrderPlaced should be ignored: %v %v", job, err)
	}
	for _, bad := range []string{`not json`, `{"type":"order.OrderConfirmed"}`,
		`{"eventId":"e4","type":"order.OrderConfirmed","payload":{"orderId":"o","customerEmail":"no-at-sign"}}`,
		`{"eventId":"e5","type":"order.OrderConfirmed","payload":{"orderId":"o","customerEmail":"a@b.c\r\nBcc: x@y.z"}}`} {
		if _, err := ParseEvent([]byte(bad)); !errors.Is(err, ErrBadEvent) {
			t.Errorf("%s: want ErrBadEvent, got %v", bad, err)
		}
	}
}

func TestRetryLadderThenDead(t *testing.T) {
	cases := []struct {
		attempt   int
		permanent bool
		outcome   Outcome
		delay     int
	}{{1, false, Retry, 0}, {2, false, Retry, 1}, {3, false, Retry, 2}, {4, false, Retry, 2}, {5, false, Dead, -1}, {1, true, Dead, -1}}
	for _, c := range cases {
		o, d := AfterFailure(c.attempt, c.permanent)
		if o != c.outcome || d != c.delay {
			t.Errorf("attempt %d permanent %v: got %v/%d want %v/%d", c.attempt, c.permanent, o, d, c.outcome, c.delay)
		}
	}
}
