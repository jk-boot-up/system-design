package com.jkbootup.ecommerce.catalog.application;

import com.jkbootup.ecommerce.catalog.application.port.ImageStorage;
import com.jkbootup.ecommerce.catalog.domain.Category;
import com.jkbootup.ecommerce.catalog.domain.DomainException;
import com.jkbootup.ecommerce.catalog.domain.Money;
import com.jkbootup.ecommerce.catalog.domain.Product;
import com.jkbootup.ecommerce.catalog.domain.ProductDetails;
import com.jkbootup.ecommerce.catalog.domain.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogServiceTest {

    InMemoryCatalog db;
    CatalogService service;
    Category shoes;

    @BeforeEach
    void setUp() {
        db = new InMemoryCatalog();
        service = new CatalogService(db, db.categoryRepository, db, db, db, db,
                Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC));
        Category running = service.createCategory("running", "Running", null);
        shoes = service.createCategory("running-shoes", "Running shoes", running.id());
    }

    ProductDetails details(String price, ProductStatus status) {
        return new ProductDetails("Trail Runner 2", "", shoes.id(), "Acme", Money.of(price, "EUR"), Map.of(), status);
    }

    @Test
    void createWritesTheProductAndItsEventInOneTransaction() {
        Product p = service.createProduct("SKU-1001", details("89.90", ProductStatus.ACTIVE));
        assertThat(db.products).containsKey(p.id());
        assertThat(db.published).extracting(Product::version).containsExactly(1L);
        assertThat(db.log).containsExactly("commit", "commit", "event v1", "commit");
    }

    @Test
    void readsAreServedFromTheCacheAfterTheFirstMiss() {
        Product p = service.createProduct("SKU-1001", details("89.90", ProductStatus.ACTIVE));
        int before = db.dbReads;
        service.activeProduct(p.id());
        service.activeProduct(p.id());
        service.activeProduct(p.id());
        assertThat(db.dbReads - before).isEqualTo(1);
    }

    @Test
    void shoppersCannotSeeDrafts() {
        Product draft = service.createProduct("SKU-1002", details("10", ProductStatus.DRAFT));
        assertThatThrownBy(() -> service.activeProduct(draft.id())).extracting(e -> ((DomainException) e).code()).isEqualTo("NOT_FOUND");
        assertThat(service.product(draft.id()).sku()).isEqualTo("SKU-1002");
    }

    @Test
    void updateWithTheCurrentVersionSucceedsAndEvictsTheCacheAfterCommit() {
        Product p = service.createProduct("SKU-1001", details("89.90", ProductStatus.ACTIVE));
        service.activeProduct(p.id());                          // now cached at version 1
        db.log.clear();
        Product updated = service.updateProduct(p.id(), 1, details("79.90", ProductStatus.ACTIVE));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(db.log).containsExactly("event v2", "commit", "evict after commit");
        assertThat(service.activeProduct(p.id()).details().price().amount()).isEqualByComparingTo("79.90");
    }

    @Test
    void staleVersionIsRejectedAndNothingChanges() {
        Product p = service.createProduct("SKU-1001", details("89.90", ProductStatus.ACTIVE));
        service.updateProduct(p.id(), 1, details("79.90", ProductStatus.ACTIVE));
        assertThatThrownBy(() -> service.updateProduct(p.id(), 1, details("1.00", ProductStatus.ACTIVE)))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("VERSION_MISMATCH");
        assertThat(db.products.get(p.id()).details().price().amount()).isEqualByComparingTo("79.90");
        assertThat(db.published).hasSize(2);
    }

    @Test
    void aWriteThatCommitsBetweenTheCheckAndTheUpdateIsCaughtByTheConditionalUpdate() {
        Product p = service.createProduct("SKU-1001", details("89.90", ProductStatus.ACTIVE));
        db.beforeUpdate = () -> {
            db.beforeUpdate = () -> {};
            Product other = db.products.get(p.id()).update(details("50.00", ProductStatus.ACTIVE), Instant.EPOCH);
            db.products.put(p.id(), other);                     // another editor wins the race
            db.otherCommits.put(p.id(), other);
        };
        assertThatThrownBy(() -> service.updateProduct(p.id(), 1, details("79.90", ProductStatus.ACTIVE)))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("VERSION_MISMATCH");
        assertThat(db.products.get(p.id()).details().price().amount()).isEqualByComparingTo("50.00");
        assertThat(db.published).hasSize(1);                    // no event for the lost write
    }

    @Test
    void pricesReturnKnownSkusOnly() {
        service.createProduct("SKU-1001", details("89.90", ProductStatus.ACTIVE));
        assertThat(service.pricesFor(List.of("SKU-1001", "SKU-404", "SKU-1001"))).extracting(Product::sku).containsExactly("SKU-1001");
        assertThatThrownBy(() -> service.pricesFor(java.util.stream.IntStream.range(0, 101).mapToObj(i -> "SKU-" + i).toList()))
                .hasMessageContaining("At most 100");
    }

    @Test
    void imageUploadIsSignedThenConfirmedOnlyWhenTheObjectExists() {
        Product p = service.createProduct("SKU-1001", details("89.90", ProductStatus.ACTIVE));
        ImageStorage.UploadTicket ticket = service.startImageUpload(p.id(), "image/webp", 120_000);
        assertThat(ticket.objectKey()).startsWith("products/" + p.id() + "/").endsWith(".webp");

        assertThatThrownBy(() -> service.confirmImage(p.id(), ticket.objectKey()))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("IMAGE_MISSING");

        db.objects.put(ticket.objectKey(), new ImageStorage.StoredObject("image/webp", 120_000));
        Product withImage = service.confirmImage(p.id(), ticket.objectKey());
        assertThat(withImage.images()).hasSize(1);
        assertThat(service.confirmImage(p.id(), ticket.objectKey()).version()).isEqualTo(withImage.version());   // idempotent
    }

    @Test
    void imageRulesAreChecked() {
        Product p = service.createProduct("SKU-1001", details("89.90", ProductStatus.ACTIVE));
        assertThatThrownBy(() -> service.startImageUpload(p.id(), "image/gif", 10)).hasMessageContaining("JPEG, PNG or WebP");
        assertThatThrownBy(() -> service.startImageUpload(p.id(), "image/png", 6_000_000)).hasMessageContaining("5 MB");
        assertThatThrownBy(() -> service.confirmImage(p.id(), "products/" + UUID.randomUUID() + "/x.png"))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INVALID_OBJECT_KEY");
    }

    @Test
    void categoriesFormATree() {
        assertThat(service.categoryTree()).singleElement().satisfies(root -> {
            assertThat(root.category().slug()).isEqualTo("running");
            assertThat(root.children()).extracting(c -> c.category().slug()).containsExactly("running-shoes");
        });
        assertThatThrownBy(() -> service.createCategory("x", "X", UUID.randomUUID()))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("UNKNOWN_CATEGORY");
    }

    @Test
    void unknownCategoryRollsBackTheProduct() {
        assertThatThrownBy(() -> service.createProduct("SKU-9", new ProductDetails("X", "", UUID.randomUUID(), "Acme",
                Money.of("1", "EUR"), Map.of(), ProductStatus.DRAFT))).hasMessageContaining("does not exist");
        assertThat(db.products).isEmpty();
    }
}
