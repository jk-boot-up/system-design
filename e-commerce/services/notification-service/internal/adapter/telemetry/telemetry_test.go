package telemetry

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"testing"

	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/twmb/franz-go/pkg/kgo"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

const parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

func TestTraceContextTravelsFromAKafkaRecordToARabbitMQMessage(t *testing.T) {
	p := propagation.TraceContext{}
	r := &kgo.Record{Headers: []kgo.RecordHeader{{Key: "x-other", Value: []byte("kept")}, {Key: "traceparent", Value: []byte(parent)}}}
	ctx := p.Extract(context.Background(), KafkaHeaders{R: r})
	if got := trace.SpanContextFromContext(ctx).TraceID().String(); got != "4bf92f3577b34da6a3ce929d0e0e4736" {
		t.Fatalf("trace id %q", got)
	}
	headers := amqp.Table{"x-attempt": int32(1)}
	p.Inject(ctx, AMQPHeaders(headers))
	if headers["traceparent"] != parent || headers["x-attempt"] != int32(1) {
		t.Fatalf("headers %v", headers)
	}
	back := p.Extract(context.Background(), AMQPHeaders(headers))
	if !trace.SpanContextFromContext(back).Equal(trace.SpanContextFromContext(ctx)) {
		t.Fatal("trace context changed on the way through RabbitMQ")
	}
	KafkaHeaders{R: r}.Set("traceparent", "replaced")
	if len(r.Headers) != 2 || (KafkaHeaders{R: r}).Get("traceparent") != "replaced" {
		t.Fatalf("set should replace, not append: %v", r.Headers)
	}
}

func TestLogLinesCarryTheTraceOfTheirContext(t *testing.T) {
	var out bytes.Buffer
	log := slog.New(LogHandler{Handler: slog.NewJSONHandler(&out, nil)}).With("service", "notification-service")
	ctx := propagation.TraceContext{}.Extract(context.Background(), AMQPHeaders(amqp.Table{"traceparent": parent}))
	log.InfoContext(ctx, "sent")
	log.Info("no trace")
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	if !strings.Contains(lines[0], `"traceId":"4bf92f3577b34da6a3ce929d0e0e4736"`) || !strings.Contains(lines[0], `"spanId":"00f067aa0ba902b7"`) {
		t.Fatalf("line %s", lines[0])
	}
	if strings.Contains(lines[1], "traceId") {
		t.Fatalf("line %s", lines[1])
	}
}

func TestWithoutAnEndpointNothingIsExported(t *testing.T) {
	t.Setenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
	shutdown, err := Setup(context.Background(), "notification-service")
	if err != nil || shutdown(context.Background()) != nil {
		t.Fatal(err)
	}
}
