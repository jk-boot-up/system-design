package smtp

import (
	"errors"
	"net/textproto"
	"strings"
	"testing"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/app"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/domain"
)

func TestClassify(t *testing.T) {
	if !app.IsPermanent(classify(&textproto.Error{Code: 550, Msg: "no such mailbox"})) {
		t.Error("550 should be permanent")
	}
	if app.IsPermanent(classify(&textproto.Error{Code: 451, Msg: "try later"})) || app.IsPermanent(classify(errors.New("reset"))) {
		t.Error("4xx and network errors should be temporary")
	}
}

func TestMessageHeaders(t *testing.T) {
	m := string(message("Shop <orders@shop.example>", domain.EmailJob{EventID: "e1", To: "ada@example.com", Subject: "Hi", Body: "a\nb"}))
	for _, want := range []string{"From: Shop <orders@shop.example>\r\n", "Message-ID: <e1@notification.shop.example>\r\n", "\r\n\r\na\r\nb"} {
		if !strings.Contains(m, want) {
			t.Errorf("missing %q in\n%s", want, m)
		}
	}
}
