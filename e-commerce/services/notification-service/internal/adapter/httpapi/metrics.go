// Package httpapi serves health checks and Prometheus metrics.
package httpapi

import (
	"net/http"
	"sync/atomic"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/app"
)

type Metrics struct {
	events *prometheus.CounterVec
	emails *prometheus.CounterVec
	send   prometheus.Histogram
}

func NewMetrics(reg prometheus.Registerer) *Metrics {
	f := promauto.With(reg)
	return &Metrics{
		events: f.NewCounterVec(prometheus.CounterOpts{Name: "notification_events_total", Help: "Kafka events by outcome"}, []string{"result"}),
		emails: f.NewCounterVec(prometheus.CounterOpts{Name: "notification_emails_total", Help: "Email jobs by outcome"}, []string{"result"}),
		send:   f.NewHistogram(prometheus.HistogramOpts{Name: "notification_job_seconds", Help: "Time to handle one job", Buckets: prometheus.DefBuckets}),
	}
}

func (m *Metrics) Event(r app.RouteResult) { m.events.WithLabelValues(string(r)).Inc() }

func (m *Metrics) Email(r app.SendResult, d time.Duration) {
	m.emails.WithLabelValues(string(r)).Inc()
	m.send.Observe(d.Seconds())
}

// Handler serves /healthz (process alive), /readyz (connected to RabbitMQ and Kafka) and /metrics.
func Handler(ready *atomic.Bool, gatherer prometheus.Gatherer) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok\n")) })
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, _ *http.Request) {
		if !ready.Load() {
			http.Error(w, "starting", http.StatusServiceUnavailable)
			return
		}
		_, _ = w.Write([]byte("ready\n"))
	})
	mux.Handle("GET /metrics", promhttp.HandlerFor(gatherer, promhttp.HandlerOpts{}))
	return mux
}
