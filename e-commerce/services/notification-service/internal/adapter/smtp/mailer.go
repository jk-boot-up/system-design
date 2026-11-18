// Package smtp sends email over SMTP (Mailpit locally, the provider's relay in production).
package smtp

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/mail"
	"net/smtp"
	"net/textproto"
	"strings"
	"time"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/app"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/domain"
)

type Mailer struct {
	Addr    string // host:port
	From    string
	Timeout time.Duration
}

func (m Mailer) Send(ctx context.Context, job domain.EmailJob) error {
	ctx, cancel := context.WithTimeout(ctx, m.Timeout)
	defer cancel()
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", m.Addr)
	if err != nil {
		return err // temporary: the server may be back soon
	}
	deadline, _ := ctx.Deadline()
	_ = conn.SetDeadline(deadline)
	host, _, _ := net.SplitHostPort(m.Addr)
	c, err := smtp.NewClient(conn, host)
	if err != nil {
		conn.Close()
		return classify(err)
	}
	defer c.Close()
	// The envelope sender is the bare address; the From header keeps the display name.
	from, err := mail.ParseAddress(m.From)
	if err != nil {
		return &app.PermanentError{Err: err}
	}
	if err := c.Mail(from.Address); err != nil {
		return classify(err)
	}
	if err := c.Rcpt(job.To); err != nil {
		return classify(err)
	}
	w, err := c.Data()
	if err != nil {
		return classify(err)
	}
	if _, err := w.Write(message(m.From, job)); err != nil {
		return classify(err)
	}
	if err := w.Close(); err != nil {
		return classify(err)
	}
	return classify(c.Quit())
}

// classify marks 5xx replies as permanent; everything else (4xx, network) is worth retrying.
func classify(err error) error {
	var te *textproto.Error
	if errors.As(err, &te) && te.Code >= 500 {
		return &app.PermanentError{Err: err}
	}
	return err
}

func message(from string, job domain.EmailJob) []byte {
	var b strings.Builder
	fmt.Fprintf(&b, "From: %s\r\nTo: %s\r\nSubject: %s\r\n", from, job.To, job.Subject)
	fmt.Fprintf(&b, "Message-ID: <%s@notification.shop.example>\r\nDate: %s\r\n", job.EventID, time.Now().UTC().Format(time.RFC1123Z))
	b.WriteString("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n")
	fmt.Fprintf(&b, "X-Event-Id: %s\r\n\r\n", job.EventID)
	b.WriteString(strings.ReplaceAll(job.Body, "\n", "\r\n"))
	return []byte(b.String())
}
