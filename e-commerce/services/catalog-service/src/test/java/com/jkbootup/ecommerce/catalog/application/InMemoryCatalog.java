package com.jkbootup.ecommerce.catalog.application;

import com.jkbootup.ecommerce.catalog.application.port.CategoryRepository;
import com.jkbootup.ecommerce.catalog.application.port.ImageStorage;
import com.jkbootup.ecommerce.catalog.application.port.ProductCache;
import com.jkbootup.ecommerce.catalog.application.port.ProductEvents;
import com.jkbootup.ecommerce.catalog.application.port.ProductRepository;
import com.jkbootup.ecommerce.catalog.application.port.Transactions;
import com.jkbootup.ecommerce.catalog.domain.Category;
import com.jkbootup.ecommerce.catalog.domain.DomainException;
import com.jkbootup.ecommerce.catalog.domain.Product;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Test doubles for every port, with a log of what happened in which order. */
class InMemoryCatalog implements ProductRepository, ProductEvents, ProductCache, ImageStorage, Transactions {

    final Map<UUID, Product> products = new HashMap<>();
    final Map<UUID, Category> categories = new HashMap<>();
    final Map<UUID, Product> cached = new HashMap<>();
    final Map<String, StoredObject> objects = new HashMap<>();
    final List<String> log = new ArrayList<>();
    final List<Product> published = new ArrayList<>();
    boolean inTx;
    int dbReads;
    /** Runs inside update(), just before the conditional write: simulates another editor committing first. */
    Runnable beforeUpdate = () -> {};
    /** Writes made by "other editors": they committed in their own transaction, so a rollback here keeps them. */
    final Map<UUID, Product> otherCommits = new HashMap<>();

    @Override
    public Optional<Product> findById(UUID id) {
        dbReads++;
        return Optional.ofNullable(products.get(id));
    }

    @Override
    public List<Product> findBySkus(Collection<String> skus) {
        return products.values().stream().filter(p -> skus.contains(p.sku())).toList();
    }

    @Override
    public void insert(Product p) {
        if (products.values().stream().anyMatch(o -> o.sku().equals(p.sku())))
            throw new DomainException("SKU_TAKEN", "taken");
        products.put(p.id(), p);
    }

    @Override
    public boolean update(Product p, long expectedVersion) {
        beforeUpdate.run();
        if (products.get(p.id()).version() != expectedVersion) return false;
        products.put(p.id(), p);
        return true;
    }

    final CategoryRepository categoryRepository = new CategoryRepository() {
        @Override
        public List<Category> findAll() {
            return List.copyOf(categories.values());
        }

        @Override
        public Optional<Category> findById(UUID id) {
            return Optional.ofNullable(categories.get(id));
        }

        @Override
        public void insert(Category c) {
            categories.put(c.id(), c);
        }
    };

    @Override
    public void productChanged(Product p, Category c) {
        if (!inTx) throw new AssertionError("event published outside the transaction");
        published.add(p);
        log.add("event v" + p.version());
    }

    @Override
    public Optional<Product> get(UUID id) {
        return Optional.ofNullable(cached.get(id));
    }

    @Override
    public void put(Product p) {
        cached.put(p.id(), p);
    }

    @Override
    public void evict(UUID id) {
        log.add(inTx ? "evict inside tx" : "evict after commit");
        cached.remove(id);
    }

    @Override
    public UploadTicket presignUpload(String key, String contentType, Duration validFor) {
        return new UploadTicket("http://storage/" + key + "?signature", key, Instant.EPOCH.plus(validFor));
    }

    @Override
    public Optional<StoredObject> find(String key) {
        return Optional.ofNullable(objects.get(key));
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        Map<UUID, Product> snapshot = new HashMap<>(products);
        int events = published.size();
        inTx = true;
        try {
            T result = work.get();
            log.add("commit");
            return result;
        } catch (RuntimeException e) {
            products.clear();
            products.putAll(snapshot);
            products.putAll(otherCommits);
            published.subList(events, published.size()).clear();
            log.add("rollback");
            throw e;
        } finally {
            inTx = false;
        }
    }
}
