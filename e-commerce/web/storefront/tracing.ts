// Distributed tracing for the storefront (design/16 §16.11, SPEC 8.2). server.ts imports this module first: ES modules
// are evaluated in import order, so Node's HTTP server is instrumented before Express is loaded.
//
// Spans: one per incoming page or data request (probes, metrics and static assets excluded) and one per outgoing fetch,
// which carries the W3C traceparent to the API gateway: a page's trace continues through every service it calls.
// Exported over OTLP when OTEL_EXPORTER_OTLP_TRACES_ENDPOINT is set (compose: Alloy); without it nothing is recorded.
// Request paths and URLs are recorded without their query strings, which can hold search terms.
import { context, propagation, trace } from '@opentelemetry/api';
import { AsyncLocalStorageContextManager } from '@opentelemetry/context-async-hooks';
import { W3CTraceContextPropagator } from '@opentelemetry/core';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-proto';
import { HttpInstrumentation } from '@opentelemetry/instrumentation-http';
import { UndiciInstrumentation } from '@opentelemetry/instrumentation-undici';
import { resourceFromAttributes } from '@opentelemetry/resources';
import { BatchSpanProcessor, ParentBasedSampler, TraceIdRatioBasedSampler, TracerProvider } from '@opentelemetry/sdk-trace';
import { ATTR_SERVICE_NAME } from '@opentelemetry/semantic-conventions';

const endpoint = process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT;
const UNTRACED = /^\/(healthz|readyz|metrics|assets\/|favicon)/;

let provider: TracerProvider | undefined;

/** Sends the spans still buffered; called at the end of a graceful shutdown. */
export async function flushTraces(): Promise<void> {
  await provider?.shutdown().catch(() => undefined);
}

if (endpoint) {
  const ratio = Number(process.env.TRACING_SAMPLING_PROBABILITY ?? 1);
  provider = new TracerProvider({
    resource: resourceFromAttributes({ [ATTR_SERVICE_NAME]: process.env.OTEL_SERVICE_NAME ?? 'storefront' }),
    // A request the edge already sampled stays sampled; a new trace is sampled at the configured ratio.
    sampler: new ParentBasedSampler({ root: new TraceIdRatioBasedSampler(Number.isFinite(ratio) ? ratio : 1) }),
    spanProcessors: [new BatchSpanProcessor({ exporter: new OTLPTraceExporter({ url: endpoint }) })],
  });
  context.setGlobalContextManager(new AsyncLocalStorageContextManager().enable());
  propagation.setGlobalPropagator(new W3CTraceContextPropagator());
  trace.setGlobalTracerProvider(provider);

  const withoutQuery = (url: string | undefined) => (url ?? '').split('?')[0];
  new HttpInstrumentation({
    ignoreIncomingRequestHook: (req) => UNTRACED.test(req.url ?? ''),
    ignoreOutgoingRequestHook: () => true,    // outgoing calls use fetch (undici), traced below
    startIncomingSpanHook: (req) => ({ 'url.path': withoutQuery(req.url) }),
    requestHook: (span, req) => {
      if (!('url' in req)) return;
      span.updateName(`${req.method} ${withoutQuery(req.url)}`);
      span.setAttributes({ 'http.target': withoutQuery(req.url), 'url.query': '' });   // defaults include the query
    },
  });
  new UndiciInstrumentation({
    requestHook: (span, req) => span.setAttribute('url.full', `${req.origin}${withoutQuery(req.path)}`),
  });
}
