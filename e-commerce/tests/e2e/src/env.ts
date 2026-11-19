// Where the stack is. Defaults are the local compose stack (deploy/compose); override for a Kubernetes environment.
const e = process.env;

export const env = {
  /** Public entry point: the NGINX edge. */
  edge: e.E2E_EDGE ?? 'https://localhost:8443',
  /** Keycloak token endpoint (password grant for the test users; dev realm only). */
  tokenUrl: e.E2E_TOKEN_URL ?? 'http://localhost:8180/realms/shop/protocol/openid-connect/token',
  clientId: e.E2E_CLIENT_ID ?? 'shop-web',
  /** Self-signed certificate locally. */
  insecureTls: (e.E2E_INSECURE_TLS ?? 'true') === 'true',
  /** Read-only checks that are not part of the public API (stock levels, delivered email, analytics rows). */
  inventory: e.E2E_INVENTORY ?? 'http://localhost:8084',
  mailpit: e.E2E_MAILPIT ?? 'http://localhost:8025',
  cassandra: e.E2E_CASSANDRA ?? 'localhost:9042',
  cassandraDc: e.E2E_CASSANDRA_DC ?? 'datacenter1',
  clickhouse: e.E2E_CLICKHOUSE ?? 'http://localhost:8123',
  clickhouseUser: e.E2E_CLICKHOUSE_USER ?? 'grafana',
  clickhousePassword: e.E2E_CLICKHOUSE_PASSWORD ?? 'grafana',
  /**
   * Optional parts of the stack (compose profiles). A journey that needs one is skipped when it is not running,
   * unless E2E_REQUIRE lists it (or says "all"): then it fails, as it should in a nightly run of the full stack.
   */
  health: {
    search: e.E2E_HEALTH_SEARCH ?? 'http://localhost:8082/actuator/health/readiness',
    reviews: e.E2E_HEALTH_REVIEWS ?? 'http://localhost:8087/actuator/health/readiness',
    async: e.E2E_HEALTH_ASYNC ?? 'http://localhost:8088/readyz',
    recommendations: e.E2E_HEALTH_RECOMMENDATIONS ?? 'http://localhost:8089/readyz',
    analytics: e.E2E_HEALTH_ANALYTICS ?? 'http://localhost:8123/ping',
  },
  require: new Set((e.E2E_REQUIRE ?? '').split(',').map((s) => s.trim()).filter(Boolean)),
};
