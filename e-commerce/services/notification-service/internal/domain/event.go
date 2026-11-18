// Package domain holds the notification rules: which events become which emails, how urgent they are, and when a failed
// email is retried or given up. It has no I/O.
package domain

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// Envelope is the common shape of every event on Kafka (design/10-event-catalogue.md).
type Envelope struct {
	EventID     string          `json:"eventId"`
	Type        string          `json:"type"`
	Version     int             `json:"version"`
	OccurredAt  string          `json:"occurredAt"`
	AggregateID string          `json:"aggregateId"`
	Payload     json.RawMessage `json:"payload"`
}

type Money struct {
	Amount   string `json:"amount"`
	Currency string `json:"currency"`
}

func (m Money) String() string { return m.Amount + " " + m.Currency }

type Line struct {
	SKU         string `json:"sku"`
	ProductName string `json:"productName"`
	Quantity    int    `json:"quantity"`
	UnitPrice   Money  `json:"unitPrice"`
}

type orderConfirmed struct {
	OrderID       string `json:"orderId"`
	CustomerEmail string `json:"customerEmail"`
	Total         Money  `json:"total"`
	Lines         []Line `json:"lines"`
}

type orderCancelled struct {
	OrderID       string `json:"orderId"`
	CustomerEmail string `json:"customerEmail"`
	Reason        string `json:"reason"`
	Total         Money  `json:"total"`
}

// ErrBadEvent marks an event that can never be turned into an email (unreadable or missing fields): it goes to the
// dead-letter topic instead of being retried.
var ErrBadEvent = errors.New("bad event")

// ParseEvent reads a Kafka record value. It returns a job, or nil for event types that send no email.
func ParseEvent(value []byte) (*EmailJob, error) {
	var e Envelope
	if err := json.Unmarshal(value, &e); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrBadEvent, err)
	}
	if e.EventID == "" || e.Type == "" {
		return nil, fmt.Errorf("%w: eventId and type are required", ErrBadEvent)
	}
	switch e.Type {
	case "order.OrderConfirmed":
		var p orderConfirmed
		if err := json.Unmarshal(e.Payload, &p); err != nil || p.OrderID == "" || !validEmail(p.CustomerEmail) {
			return nil, fmt.Errorf("%w: OrderConfirmed %s needs orderId and a customer email", ErrBadEvent, e.EventID)
		}
		return confirmationEmail(e.EventID, p), nil
	case "order.OrderCancelled":
		var p orderCancelled
		if err := json.Unmarshal(e.Payload, &p); err != nil || p.OrderID == "" || !validEmail(p.CustomerEmail) {
			return nil, fmt.Errorf("%w: OrderCancelled %s needs orderId and a customer email", ErrBadEvent, e.EventID)
		}
		return cancellationEmail(e.EventID, p), nil
	default:
		return nil, nil
	}
}

func validEmail(s string) bool {
	at := strings.IndexByte(s, '@')
	return at > 0 && at < len(s)-1 && !strings.ContainsAny(s, " \r\n<>")
}
