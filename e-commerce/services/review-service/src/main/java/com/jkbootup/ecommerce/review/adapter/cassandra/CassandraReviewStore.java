package com.jkbootup.ecommerce.review.adapter.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.jkbootup.ecommerce.review.application.port.PurchaseLedger;
import com.jkbootup.ecommerce.review.application.port.ReviewStore;
import com.jkbootup.ecommerce.review.domain.RatingSummary;
import com.jkbootup.ecommerce.review.domain.Review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reviews in Cassandra, one table per query (see cassandra/schema.cql). Statements are prepared once. */
public class CassandraReviewStore implements ReviewStore, PurchaseLedger {

    private static final String REVIEW_COLUMNS = "review_id, sku, customer_id, rating, title, body, verified, created_at";

    private final CqlSession session;
    private final PreparedStatement claim, append, appendBucket, markCounted, count, summary, firstPage, nextPage, buckets,
            recordPurchase, hasBought;

    public CassandraReviewStore(CqlSession session) {
        this.session = session;
        claim = session.prepare("INSERT INTO reviews.review_guard (" + REVIEW_COLUMNS + ", counted) VALUES (?, ?, ?, ?, ?, ?, ?, ?, false) IF NOT EXISTS");
        append = session.prepare("INSERT INTO reviews.reviews_by_product (bucket, " + REVIEW_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        appendBucket = session.prepare("INSERT INTO reviews.review_buckets_by_product (sku, bucket) VALUES (?, ?)");
        markCounted = session.prepare("UPDATE reviews.review_guard SET counted = true WHERE sku = ? AND customer_id = ? IF counted = false");
        count = session.prepare("UPDATE reviews.rating_summary_by_product SET review_count = review_count + 1, rating_total = rating_total + ? WHERE sku = ?");
        summary = session.prepare("SELECT review_count, rating_total FROM reviews.rating_summary_by_product WHERE sku = ?");
        firstPage = session.prepare("SELECT " + REVIEW_COLUMNS + " FROM reviews.reviews_by_product WHERE sku = ? AND bucket = ? LIMIT ?");
        nextPage = session.prepare("SELECT " + REVIEW_COLUMNS + " FROM reviews.reviews_by_product WHERE sku = ? AND bucket = ? "
                + "AND (created_at, review_id) < (?, ?) LIMIT ?");
        buckets = session.prepare("SELECT bucket FROM reviews.review_buckets_by_product WHERE sku = ?");
        recordPurchase = session.prepare("INSERT INTO reviews.purchases_by_customer (customer_id, sku, purchased_at) VALUES (?, ?, ?)");
        hasBought = session.prepare("SELECT sku FROM reviews.purchases_by_customer WHERE customer_id = ? AND sku = ?");
    }

    @Override
    public Optional<Claim> claim(Review r) {
        ResultSet rs = session.execute(claim.bind(r.id(), r.sku(), r.customerId(), r.rating(), r.title(), r.body(), r.verified(), r.createdAt()));
        if (rs.wasApplied()) return Optional.empty();
        Row existing = rs.one();
        return Optional.of(new Claim(review(existing), existing.getBoolean("counted")));
    }

    @Override
    public void append(Review r) {
        session.execute(append.bind(r.bucket(), r.id(), r.sku(), r.customerId(), r.rating(), r.title(), r.body(), r.verified(), r.createdAt()));
        session.execute(appendBucket.bind(r.sku(), r.bucket()));
    }

    @Override
    public boolean markCounted(String sku, UUID customerId) {
        return session.execute(markCounted.bind(sku, customerId)).wasApplied();
    }

    @Override
    public void count(String sku, int rating) {
        session.execute(count.bind((long) rating, sku));
    }

    @Override
    public RatingSummary summary(String sku) {
        Row row = session.execute(summary.bind(sku)).one();
        return row == null ? RatingSummary.EMPTY : new RatingSummary(row.getLong("review_count"), row.getLong("rating_total"));
    }

    @Override
    public List<Review> page(String sku, String bucket, Review after, int limit) {
        ResultSet rs = after == null ? session.execute(firstPage.bind(sku, bucket, limit))
                : session.execute(nextPage.bind(sku, bucket, after.createdAt(), after.id(), limit));
        return rs.all().stream().map(CassandraReviewStore::review).toList();
    }

    @Override
    public List<String> buckets(String sku) {
        return session.execute(buckets.bind(sku)).all().stream().map(r -> r.getString("bucket")).toList();
    }

    @Override
    public void record(UUID customerId, String sku, Instant purchasedAt) {
        session.execute(recordPurchase.bind(customerId, sku, purchasedAt));
    }

    @Override
    public boolean hasBought(UUID customerId, String sku) {
        return session.execute(hasBought.bind(customerId, sku)).one() != null;
    }

    private static Review review(Row row) {
        return new Review(row.getUuid("review_id"), row.getString("sku"), row.getUuid("customer_id"), row.getInt("rating"),
                row.getString("title"), row.getString("body"), row.getBoolean("verified"), row.getInstant("created_at"));
    }
}
