// notification-service: order events → RabbitMQ email jobs → SMTP. Router and worker run in one process; set
// ROLE=router or ROLE=worker to scale them separately.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/redis/go-redis/v9"
	"golang.org/x/sync/errgroup"

	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/dedupe"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/httpapi"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/kafka"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/rabbit"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/smtp"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/adapter/telemetry"
	"github.com/jkbootup/system-design/e-commerce/notification-service/internal/app"
)

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func main() {
	slog.SetDefault(slog.New(telemetry.LogHandler{Handler: slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})}).With("service", "notification-service"))
	if err := run(); err != nil && !errors.Is(err, context.Canceled) {
		slog.Error("stopped", "err", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	role := env("ROLE", "all")
	shutdownTracing, err := telemetry.Setup(ctx, "notification-service")
	if err != nil {
		return err
	}
	defer func() {
		flush, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = shutdownTracing(flush)
	}()
	delays, err := rabbit.ParseDelays(env("RETRY_DELAYS", "30s,2m,10m"))
	if err != nil {
		return err
	}

	broker, err := rabbit.Dial(env("RABBITMQ_URL", "amqp://guest:guest@localhost:5672/"), delays)
	if err != nil {
		return err
	}
	defer broker.Close()
	ropts, err := redis.ParseURL(env("REDIS_URL", "redis://localhost:6379"))
	if err != nil {
		return err
	}
	ropts.ReadTimeout, ropts.WriteTimeout = 200*time.Millisecond, 200*time.Millisecond
	rdb := redis.NewClient(ropts)
	defer rdb.Close()
	consumer, err := kafka.New(strings.Split(env("KAFKA_BROKERS", "localhost:9092"), ","))
	if err != nil {
		return err
	}
	defer consumer.Close()

	reg := prometheus.NewRegistry()
	reg.MustRegister(collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}))
	metrics := httpapi.NewMetrics(reg)
	var ready atomic.Bool
	srv := &http.Server{Addr: ":" + env("PORT", "8088"), Handler: httpapi.Handler(&ready, reg), ReadHeaderTimeout: 5 * time.Second}

	g, ctx := errgroup.WithContext(ctx)
	g.Go(func() error {
		if err := srv.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return nil
	})
	g.Go(func() error {
		select {
		case e := <-broker.Closed():
			return errors.New("rabbitmq connection lost: " + e.Error())
		case <-ctx.Done():
		}
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return srv.Shutdown(shutdown)
	})
	if role == "all" || role == "router" {
		router := app.Router{Queue: broker, Dead: consumer}
		g.Go(func() error { return consumer.Run(ctx, router, metrics.Event) })
	}
	if role == "all" || role == "worker" {
		mailer := smtp.Mailer{Addr: env("SMTP_ADDR", "localhost:1025"), From: env("MAIL_FROM", "Shop <orders@shop.example>"), Timeout: 10 * time.Second}
		worker := app.Worker{Mailer: mailer, SentLog: dedupe.RedisSentLog{R: rdb}, Scheduler: broker}
		g.Go(func() error { return broker.Consume(ctx, 20, worker, metrics.Email) })
	}
	ready.Store(true)
	slog.Info("started", "role", role, "retryDelays", delays)
	return g.Wait()
}
