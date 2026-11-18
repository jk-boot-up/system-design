package com.jkbootup.ecommerce.catalog.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    static final UUID CATEGORY = UUID.randomUUID();

    static ProductDetails details(String price, ProductStatus status) {
        return new ProductDetails("Trail Runner 2", "Light trail shoe", CATEGORY, "Acme", Money.of(price, "EUR"),
                Map.of("size", "42"), status);
    }

    @Test
    void newProductStartsAtVersionOneAndEveryChangeAddsOne() {
        Product p = Product.create(UUID.randomUUID(), "SKU-1001", details("89.90", ProductStatus.DRAFT), NOW);
        assertThat(p.version()).isEqualTo(1);
        Product active = p.update(details("89.90", ProductStatus.ACTIVE), NOW);
        assertThat(active.version()).isEqualTo(2);
        assertThat(active.isActive()).isTrue();
        assertThat(active.addImage("products/x/1.webp", "image/webp", NOW).version()).isEqualTo(3);
    }

    @Test
    void statusFollowsTheLifecycle() {
        Product p = Product.create(UUID.randomUUID(), "SKU-1001", details("89.90", ProductStatus.ACTIVE), NOW);
        Product archived = p.update(details("89.90", ProductStatus.ARCHIVED), NOW);
        assertThatThrownBy(() -> archived.update(details("89.90", ProductStatus.ACTIVE), NOW))
                .isInstanceOf(DomainException.class).hasMessageContaining("ARCHIVED to ACTIVE");
        assertThat(archived.update(details("89.90", ProductStatus.DRAFT), NOW).details().status()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void activeProductsNeedAPriceAboveZero() {
        assertThatThrownBy(() -> details("0.00", ProductStatus.ACTIVE)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> details("-1", ProductStatus.DRAFT)).isInstanceOf(DomainException.class);
        assertThat(details("0", ProductStatus.DRAFT).price().amount()).isEqualByComparingTo("0");
    }

    @Test
    void skuAndSlugFormatsAreChecked() {
        assertThatThrownBy(() -> Product.create(UUID.randomUUID(), "sku 1", details("1", ProductStatus.DRAFT), NOW))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Category(UUID.randomUUID(), null, "Running Shoes", "Running shoes"))
                .isInstanceOf(DomainException.class);
        assertThat(new Category(UUID.randomUUID(), null, "running-shoes", "Running shoes").slug()).isEqualTo("running-shoes");
    }

    @Test
    void imagesAreOrderedIdempotentAndLimited() {
        Product p = Product.create(UUID.randomUUID(), "SKU-1001", details("1", ProductStatus.DRAFT), NOW);
        Product one = p.addImage("k1", "image/png", NOW);
        assertThat(one.addImage("k1", "image/png", NOW)).isSameAs(one);
        Product full = one;
        for (int i = 2; i <= Product.MAX_IMAGES; i++) full = full.addImage("k" + i, "image/png", NOW);
        assertThat(full.images().getLast().position()).isEqualTo(Product.MAX_IMAGES);
        Product last = full;
        assertThatThrownBy(() -> last.addImage("k11", "image/png", NOW)).hasMessageContaining("at most 10");
    }
}
