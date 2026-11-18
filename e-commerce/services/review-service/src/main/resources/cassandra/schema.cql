-- Applied at start-up; every statement is idempotent. ${replication} comes from review.cassandra.replication.
CREATE KEYSPACE IF NOT EXISTS reviews WITH replication = ${replication};

-- Q1: newest reviews of a product, paged. One partition per product per month bounds partition size.
CREATE TABLE IF NOT EXISTS reviews.reviews_by_product (
  sku         text,
  bucket      text,
  created_at  timestamp,
  review_id   uuid,
  customer_id uuid,
  rating      int,
  title       text,
  body        text,
  verified    boolean,
  PRIMARY KEY ((sku, bucket), created_at, review_id)
) WITH CLUSTERING ORDER BY (created_at DESC, review_id DESC);

-- Q1b: which month buckets hold reviews of a product, newest first (so paging knows where to continue).
CREATE TABLE IF NOT EXISTS reviews.review_buckets_by_product (
  sku    text,
  bucket text,
  PRIMARY KEY ((sku), bucket)
) WITH CLUSTERING ORDER BY (bucket DESC);

-- Q2: rating summary of a product.
CREATE TABLE IF NOT EXISTS reviews.rating_summary_by_product (
  sku          text PRIMARY KEY,
  review_count counter,
  rating_total counter
);

-- Q3: has this customer bought this product? (filled from OrderConfirmed)
CREATE TABLE IF NOT EXISTS reviews.purchases_by_customer (
  customer_id  uuid,
  sku          text,
  purchased_at timestamp,
  PRIMARY KEY ((customer_id), sku)
);

-- Q4: one review per customer per product. The claim holds the whole review and whether the counters include it,
-- so an attempt that stopped half-way can be finished by the customer's retry.
CREATE TABLE IF NOT EXISTS reviews.review_guard (
  sku         text,
  customer_id uuid,
  review_id   uuid,
  rating      int,
  title       text,
  body        text,
  verified    boolean,
  created_at  timestamp,
  counted     boolean,
  PRIMARY KEY ((sku, customer_id))
);
