package domain

import (
	"fmt"
	"strings"
)

// Priorities. RabbitMQ quorum queues have two priority levels: 5 and above is "high" and is delivered before "normal".
const (
	PriorityTransactional = 8 // order confirmations and cancellations: the customer is waiting
	PriorityBulk          = 1 // newsletters and the like (not produced yet)
)

// EmailJob is one email to send. EventID is the dedupe key: the same event never sends twice.
type EmailJob struct {
	EventID  string `json:"eventId"`
	Kind     string `json:"kind"`
	To       string `json:"to"`
	Subject  string `json:"subject"`
	Body     string `json:"body"`
	Priority uint8  `json:"priority"`
	Attempt  int    `json:"attempt"` // 1 for the first try
}

func confirmationEmail(eventID string, p orderConfirmed) *EmailJob {
	var b strings.Builder
	fmt.Fprintf(&b, "Thank you for your order %s.\n\n", p.OrderID)
	for _, l := range p.Lines {
		fmt.Fprintf(&b, "  %d × %s (%s)  %s each\n", l.Quantity, l.ProductName, l.SKU, l.UnitPrice)
	}
	fmt.Fprintf(&b, "\nTotal paid: %s\n\nWe will email you again when it ships.\n", p.Total)
	return &EmailJob{EventID: eventID, Kind: "order-confirmed", To: p.CustomerEmail,
		Subject: "Your order is confirmed", Body: b.String(), Priority: PriorityTransactional, Attempt: 1}
}

// Reasons as order-service's CancelReason names them.
var reasons = map[string]string{
	"OUT_OF_STOCK":          "an item ran out of stock before we could reserve it",
	"INVENTORY_UNAVAILABLE": "we could not check our stock in time",
	"PAYMENT_DECLINED":      "the payment was declined",
	"PAYMENT_UNKNOWN":       "we could not confirm the payment",
	"CAPTURE_FAILED":        "the payment could not be completed",
	"RESERVATION_EXPIRED":   "the items were held for too long without payment",
	"CHECKOUT_ABANDONED":    "checkout was not completed",
	"CUSTOMER_REQUEST":      "you asked us to cancel it",
}

func cancellationEmail(eventID string, p orderCancelled) *EmailJob {
	why, ok := reasons[p.Reason]
	if !ok {
		why = "it could not be completed"
	}
	body := fmt.Sprintf("Your order %s was cancelled because %s.\n\nYou have not been charged %s.\n", p.OrderID, why, p.Total)
	return &EmailJob{EventID: eventID, Kind: "order-cancelled", To: p.CustomerEmail,
		Subject: "Your order was cancelled", Body: body, Priority: PriorityTransactional, Attempt: 1}
}
