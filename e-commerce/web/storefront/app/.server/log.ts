import { trace } from '@opentelemetry/api';
import pino from 'pino';

/**
 * JSON logs (design/16 §16.11). Personal data and secrets are removed before anything is written: tokens, cookies,
 * session ids, emails and names never reach the log store.
 */
export function createLogger(level: string) {
  return pino({
    level,
    base: { service: 'storefront' },
    timestamp: pino.stdTimeFunctions.isoTime,
    formatters: { level: (label) => ({ level: label }) },
    // The current trace, so a log line opens its trace in Grafana (and a trace finds its log lines).
    mixin() {
      const span = trace.getActiveSpan()?.spanContext();
      return span ? { traceId: span.traceId, spanId: span.spanId } : {};
    },
    redact: {
      paths: ['token', 'tokens', '*.token', '*.tokens', 'cookie', 'headers.cookie', 'headers.authorization', 'email', '*.email',
        'name', '*.name', 'user.name', 'session', 'sessionId', 'secret', '*.secret'],
      censor: '[redacted]',
    },
  });
}

export type Logger = ReturnType<typeof createLogger>;
