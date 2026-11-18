# ClickHouse with the analytics schema. Build: docker build -t e-commerce/analytics .
# The SQL files run once, on first start with an empty data directory; KAFKA_BROKERS must be set.
FROM clickhouse/clickhouse-server:26.8.40
COPY config.d/ /etc/clickhouse-server/config.d/
COPY users.d/ /etc/clickhouse-server/users.d/
COPY sql/ /docker-entrypoint-initdb.d/
ENV KAFKA_BROKERS=kafka:9092 CLICKHOUSE_DB=analytics CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1
EXPOSE 8123 9000 9363
