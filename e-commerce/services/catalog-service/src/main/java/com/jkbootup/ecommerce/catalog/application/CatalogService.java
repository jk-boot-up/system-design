package com.jkbootup.ecommerce.catalog.application;

import com.jkbootup.ecommerce.catalog.application.port.CategoryRepository;
import com.jkbootup.ecommerce.catalog.application.port.ImageStorage;
import com.jkbootup.ecommerce.catalog.application.port.ProductCache;
import com.jkbootup.ecommerce.catalog.application.port.ProductEvents;
import com.jkbootup.ecommerce.catalog.application.port.ProductRepository;
import com.jkbootup.ecommerce.catalog.application.port.Transactions;
import com.jkbootup.ecommerce.catalog.domain.Category;
import com.jkbootup.ecommerce.catalog.domain.DomainException;
import com.jkbootup.ecommerce.catalog.domain.Ids;
import com.jkbootup.ecommerce.catalog.domain.Product;
import com.jkbootup.ecommerce.catalog.domain.ProductDetails;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalogue use cases. Reads go through the cache (cache-aside). Every write runs in one transaction with its outbox
 * event, and the cache entry is evicted only after that transaction has committed, so a reader can never re-cache the
 * old row from a transaction that is still open.
 */
public class CatalogService {

    public static final int MAX_PRICE_LOOKUP = 100;
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    public static final Duration UPLOAD_WINDOW = Duration.ofMinutes(15);
    private static final Map<String, String> IMAGE_TYPES = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    /** A category with its children, for the public tree. */
    public record CategoryNode(Category category, List<CategoryNode> children) {}

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductEvents events;
    private final ProductCache cache;
    private final ImageStorage storage;
    private final Transactions tx;
    private final Clock clock;

    public CatalogService(ProductRepository products, CategoryRepository categories, ProductEvents events, ProductCache cache,
                          ImageStorage storage, Transactions tx, Clock clock) {
        this.products = products;
        this.categories = categories;
        this.events = events;
        this.cache = cache;
        this.storage = storage;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------- reads ----------

    /** Any product, for staff. */
    public Product product(UUID id) {
        Optional<Product> cached = cache.get(id);
        if (cached.isPresent()) return cached.get();
        Product p = products.findById(id).orElseThrow(() -> notFound(id));
        cache.put(p);
        return p;
    }

    /** A product as shoppers see it: drafts and archived products do not exist for them. */
    public Product activeProduct(UUID id) {
        Product p = product(id);
        if (!p.isActive()) throw notFound(id);
        return p;
    }

    public static final int MAX_PUBLIC_LOOKUP = 50;

    /** Active products for a few SKUs (cart lines, recommendations), in the order asked. Unknown or inactive SKUs are left out. */
    public List<Product> activeBySkus(List<String> skus) {
        var unique = new LinkedHashSet<>(skus);
        if (unique.size() > MAX_PUBLIC_LOOKUP)
            throw new DomainException("TOO_MANY_SKUS", "At most " + MAX_PUBLIC_LOOKUP + " SKUs per request");
        if (unique.isEmpty()) return List.of();
        Map<String, Product> found = new java.util.HashMap<>();
        for (Product p : products.findBySkus(unique)) if (p.isActive()) found.put(p.sku(), p);
        return unique.stream().map(found::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Current price, name and status per SKU, for cart-service. Unknown SKUs are left out. */
    public List<Product> pricesFor(Collection<String> skus) {
        var unique = new LinkedHashSet<>(skus);
        if (unique.size() > MAX_PRICE_LOOKUP)
            throw new DomainException("TOO_MANY_SKUS", "At most " + MAX_PRICE_LOOKUP + " SKUs per request");
        return unique.isEmpty() ? List.of() : products.findBySkus(unique);
    }

    public List<CategoryNode> categoryTree() {
        List<Category> all = categories.findAll();
        Map<UUID, List<Category>> byParent = all.stream().filter(c -> c.parentId() != null)
                .collect(Collectors.groupingBy(Category::parentId));
        return all.stream().filter(c -> c.parentId() == null).sorted(Comparator.comparing(Category::name))
                .map(c -> node(c, byParent)).toList();
    }

    private static CategoryNode node(Category c, Map<UUID, List<Category>> byParent) {
        List<CategoryNode> children = new ArrayList<>();
        for (Category child : byParent.getOrDefault(c.id(), List.of()).stream().sorted(Comparator.comparing(Category::name)).toList())
            children.add(node(child, byParent));
        return new CategoryNode(c, children);
    }

    // ---------- writes ----------

    public Category createCategory(String slug, String name, UUID parentId) {
        Category c = new Category(Ids.newId(), parentId, slug, name);
        return tx.inTransaction(() -> {
            if (parentId != null) categories.findById(parentId)
                    .orElseThrow(() -> new DomainException("UNKNOWN_CATEGORY", "Parent category " + parentId + " does not exist"));
            categories.insert(c);
            return c;
        });
    }

    public Product createProduct(String sku, ProductDetails details) {
        Product p = Product.create(Ids.newId(), sku, details, clock.instant());
        return tx.inTransaction(() -> {
            Category category = category(details.categoryId());
            products.insert(p);
            events.productChanged(p, category);
            return p;
        });
    }

    /**
     * Replaces a product's details if nobody changed it since the editor read version {@code expectedVersion}.
     * The version is checked twice: here, for a clear error, and in the conditional UPDATE, which also catches a
     * write that commits between the two.
     */
    public Product updateProduct(UUID id, long expectedVersion, ProductDetails details) {
        return change(id, expectedVersion, p -> p.update(details, clock.instant()));
    }

    /** Starts an image upload: returns a short-lived URL the browser PUTs the file to. Nothing is stored yet. */
    public ImageStorage.UploadTicket startImageUpload(UUID id, String contentType, long sizeBytes) {
        String ext = IMAGE_TYPES.get(contentType);
        if (ext == null) throw new DomainException("UNSUPPORTED_IMAGE_TYPE", "Images must be JPEG, PNG or WebP");
        if (sizeBytes <= 0 || sizeBytes > MAX_IMAGE_BYTES)
            throw new DomainException("IMAGE_TOO_LARGE", "Images must be between 1 byte and 5 MB");
        Product p = product(id);
        if (p.images().size() >= Product.MAX_IMAGES)
            throw new DomainException("TOO_MANY_IMAGES", "A product has at most " + Product.MAX_IMAGES + " images");
        return storage.presignUpload(p.imagePrefix() + Ids.newId() + "." + ext, contentType, UPLOAD_WINDOW);
    }

    /** Adds an uploaded image to the product once the object really exists. Repeating it changes nothing. */
    public Product confirmImage(UUID id, String objectKey) {
        Product current = products.findById(id).orElseThrow(() -> notFound(id));
        if (!objectKey.startsWith(current.imagePrefix()) || objectKey.contains(".."))
            throw new DomainException("INVALID_OBJECT_KEY", "The object key does not belong to product " + id);
        if (current.hasImage(objectKey)) return current;
        ImageStorage.StoredObject stored = storage.find(objectKey)
                .orElseThrow(() -> new DomainException("IMAGE_MISSING", "Nothing has been uploaded to " + objectKey));
        if (stored.sizeBytes() > MAX_IMAGE_BYTES) throw new DomainException("IMAGE_TOO_LARGE", "The uploaded image is larger than 5 MB");
        return change(id, current.version(), p -> p.addImage(objectKey, stored.contentType(), clock.instant()));
    }

    private Product change(UUID id, long expectedVersion, Function<Product, Product> edit) {
        Product saved = tx.inTransaction(() -> {
            Product current = products.findById(id).orElseThrow(() -> notFound(id));
            if (current.version() != expectedVersion) throw versionMismatch(id, current.version());
            Product next = edit.apply(current);
            if (next == current) return current;
            if (!products.update(next, expectedVersion)) throw versionMismatch(id, -1);
            events.productChanged(next, category(next.details().categoryId()));
            return next;
        });
        cache.evict(id);                       // after commit
        return saved;
    }

    private Category category(UUID id) {
        return categories.findById(id)
                .orElseThrow(() -> new DomainException("UNKNOWN_CATEGORY", "Category " + id + " does not exist"));
    }

    private static DomainException notFound(UUID id) {
        return new DomainException("NOT_FOUND", "Product " + id + " not found");
    }

    private static DomainException versionMismatch(UUID id, long current) {
        return new DomainException("VERSION_MISMATCH", "Product " + id + " was changed by someone else"
                + (current > 0 ? " (now version " + current + ")" : "") + "; reload it and apply your change again");
    }
}
