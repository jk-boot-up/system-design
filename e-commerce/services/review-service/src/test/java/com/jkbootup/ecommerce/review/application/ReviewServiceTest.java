package com.jkbootup.ecommerce.review.application;

import com.jkbootup.ecommerce.review.domain.DomainException;
import com.jkbootup.ecommerce.review.domain.PostOutcome;
import com.jkbootup.ecommerce.review.domain.Review;
import com.jkbootup.ecommerce.review.domain.ReviewPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewServiceTest {

    final InMemoryReviews store = new InMemoryReviews();
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));
    final ReviewService service = new ReviewService(store, store, store, clock);

    static class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void secondReviewByTheSameCustomerIsRejected() {
        UUID ada = UUID.randomUUID();
        service.post("SKU-1001", ada, 5, "Great", "");
        assertThatThrownBy(() -> service.post("SKU-1001", ada, 1, "Changed my mind", ""))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("ALREADY_REVIEWED");
        assertThat(store.summary("SKU-1001").count()).isEqualTo(1);
        service.post("SKU-2002", ada, 4, "Also good", "");                              // another product is fine
    }

    @Test
    void verifiedOnlyAfterAPurchase() {
        UUID ada = UUID.randomUUID(), bob = UUID.randomUUID();
        service.purchased(ada, List.of("SKU-1001"), clock.now);
        assertThat(service.post("SKU-1001", ada, 5, "Bought it", "").review().verified()).isTrue();
        assertThat(service.post("SKU-1001", bob, 2, "Never bought it", "").review().verified()).isFalse();
    }

    @ParameterizedTest(name = "crash before {0}")
    @ValueSource(strings = {"append", "markCounted", "count"})
    void aRetryAfterACrashFinishesTheReviewAndNeverDoubleCounts(String step) {
        UUID ada = UUID.randomUUID();
        store.crashBefore = step;
        assertThatThrownBy(() -> service.post("SKU-1001", ada, 4, "Comfy", "")).isInstanceOf(InMemoryReviews.Crash.class);

        if (step.equals("count")) {
            // The flag was flipped but the counters were not incremented: the review is complete and stays counted once at most.
            assertThatThrownBy(() -> service.post("SKU-1001", ada, 4, "Comfy", ""))
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("ALREADY_REVIEWED");
            assertThat(store.summary("SKU-1001").count()).isZero();                       // under-count by one, never over-count
            return;
        }
        PostOutcome retry = service.post("SKU-1001", ada, 1, "Different text on retry", "");
        assertThat(retry.resumed()).isTrue();
        assertThat(retry.review().title()).isEqualTo("Comfy");                          // the first attempt's review is kept
        assertThat(store.summary("SKU-1001").count()).isEqualTo(1);
        assertThat(store.summary("SKU-1001").total()).isEqualTo(4);
        assertThat(store.published).hasSize(1);
        assertThatThrownBy(() -> service.post("SKU-1001", ada, 4, "Again", "")).hasMessageContaining("already reviewed");
    }

    @Test
    void pagesRunNewestFirstAcrossMonthBuckets() {
        List<UUID> posted = new ArrayList<>();
        clock.now = Instant.parse("2026-09-30T12:00:00Z");
        for (int i = 0; i < 7; i++) {
            Review r = service.post("SKU-1001", UUID.randomUUID(), 1 + i % 5, "Review " + i, "").review();
            posted.add(r.id());
            clock.now = clock.now.plus(Duration.ofDays(9));                            // spreads over Sep, Oct, Nov
        }
        List<UUID> read = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ReviewPage page = service.reviews("SKU-1001", 3, cursor);
            page.items().forEach(r -> read.add(r.id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(read).containsExactlyElementsOf(posted.reversed());
        assertThat(service.reviews("SKU-1001", 3, null).summary().average()).isEqualByComparingTo("2.6");
        assertThatThrownBy(() -> service.reviews("SKU-1001", 3, "garbage!")).hasMessageContaining("cursor");
    }
}
