// Package telemetry sets up tracing (OpenTelemetry, OTLP over HTTP) and carries W3C trace context across Kafka record
// headers and RabbitMQ message headers, so an order's email belongs to the trace of the checkout that caused it.
package telemetry

import (
	"context"
	"log/slog"
	"os"

	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/twmb/franz-go/pkg/kgo"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.41.0"
	"go.opentelemetry.io/otel/trace"
)

// Tracer is the service's tracer; a no-op until Setup installs an exporter.
func Tracer() trace.Tracer { return otel.Tracer("notification-service") }

// Setup exports spans when OTEL_EXPORTER_OTLP_TRACES_ENDPOINT or OTEL_EXPORTER_OTLP_ENDPOINT is set (compose,
// Kubernetes); otherwise spans are not recorded, but trace context is still passed on. Every span is kept here: the
// collector samples whole traces (tail sampling).
func Setup(ctx context.Context, service string) (shutdown func(context.Context) error, err error) {
	otel.SetTextMapPropagator(propagation.TraceContext{})
	if os.Getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") == "" && os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT") == "" {
		return func(context.Context) error { return nil }, nil
	}
	exporter, err := otlptracehttp.New(ctx)
	if err != nil {
		return nil, err
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(resource.NewSchemaless(semconv.ServiceName(service))),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.AlwaysSample())),
	)
	otel.SetTracerProvider(tp)
	return tp.Shutdown, nil
}

// KafkaHeaders reads and writes trace context in a record's headers.
type KafkaHeaders struct{ R *kgo.Record }

func (h KafkaHeaders) Get(key string) string {
	for _, x := range h.R.Headers {
		if x.Key == key {
			return string(x.Value)
		}
	}
	return ""
}

func (h KafkaHeaders) Set(key, value string) {
	for i, x := range h.R.Headers {
		if x.Key == key {
			h.R.Headers[i].Value = []byte(value)
			return
		}
	}
	h.R.Headers = append(h.R.Headers, kgo.RecordHeader{Key: key, Value: []byte(value)})
}

func (h KafkaHeaders) Keys() []string {
	keys := make([]string, 0, len(h.R.Headers))
	for _, x := range h.R.Headers {
		keys = append(keys, x.Key)
	}
	return keys
}

// AMQPHeaders reads and writes trace context in a message's headers.
type AMQPHeaders amqp.Table

func (h AMQPHeaders) Get(key string) string {
	if v, ok := h[key].(string); ok {
		return v
	}
	return ""
}

func (h AMQPHeaders) Set(key, value string) { h[key] = value }

func (h AMQPHeaders) Keys() []string {
	keys := make([]string, 0, len(h))
	for k := range h {
		keys = append(keys, k)
	}
	return keys
}

// LogHandler adds traceId and spanId to every record logged with a context that holds a span, so Loki's log lines link
// to Tempo's traces as the Java services' do.
type LogHandler struct{ slog.Handler }

func (h LogHandler) Handle(ctx context.Context, r slog.Record) error {
	if sc := trace.SpanContextFromContext(ctx); sc.IsValid() {
		r.AddAttrs(slog.String("traceId", sc.TraceID().String()), slog.String("spanId", sc.SpanID().String()))
	}
	return h.Handler.Handle(ctx, r)
}

func (h LogHandler) WithAttrs(a []slog.Attr) slog.Handler { return LogHandler{h.Handler.WithAttrs(a)} }
func (h LogHandler) WithGroup(n string) slog.Handler      { return LogHandler{h.Handler.WithGroup(n)} }
