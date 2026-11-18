package com.jkbootup.ecommerce.catalog.adapter.web;

import com.jkbootup.ecommerce.catalog.application.CatalogService;
import com.jkbootup.ecommerce.catalog.application.port.ImageStorage;
import com.jkbootup.ecommerce.catalog.domain.Category;
import com.jkbootup.ecommerce.catalog.domain.DomainException;
import com.jkbootup.ecommerce.catalog.domain.Product;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Staff API (role {@code staff}). Updates are conditional: the client sends back the ETag it read in If-Match.
 * No If-Match is 428 Precondition Required; a stale one is 412 Precondition Failed.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public record ImageUploadRequest(@NotBlank String contentType, @Positive long sizeBytes) {}

    public record CategoryRequest(@NotBlank String slug, @NotBlank String name, UUID parentId) {}

    public record CategoryCreated(UUID id, UUID parentId, String slug, String name) {}

    private final CatalogService service;
    private final String imageBaseUrl;

    public AdminController(CatalogService service, ImageLinks links) {
        this.service = service;
        this.imageBaseUrl = links.baseUrl();
    }

    @PostMapping("/products")
    public ResponseEntity<ProductView> create(@Valid @RequestBody ProductRequest request) {
        if (request.sku() == null) throw new DomainException("INVALID_SKU", "A SKU is required");
        Product p = service.createProduct(request.sku(), request.details());
        return ResponseEntity.created(URI.create("/api/v1/admin/products/" + p.id())).eTag(ProductView.etag(p))
                .body(ProductView.of(p, imageBaseUrl));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ProductView> read(@PathVariable UUID id) {
        Product p = service.product(id);
        return ResponseEntity.ok().eTag(ProductView.etag(p)).body(ProductView.of(p, imageBaseUrl));
    }

    @PutMapping("/products/{id}")
    public ResponseEntity<ProductView> update(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @Valid @RequestBody ProductRequest request) {
        Product p = service.updateProduct(id, expectedVersion(ifMatch), request.details());
        return ResponseEntity.ok().eTag(ProductView.etag(p)).body(ProductView.of(p, imageBaseUrl));
    }

    @PostMapping("/products/{id}/images")
    public ResponseEntity<ImageStorage.UploadTicket> startUpload(@PathVariable UUID id, @Valid @RequestBody ImageUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.startImageUpload(id, request.contentType(), request.sizeBytes()));
    }

    /** The object key contains slashes, so it is taken from the rest of the path. */
    @PostMapping("/products/{id}/images/confirm")
    public ResponseEntity<ProductView> confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmRequest request) {
        Product p = service.confirmImage(id, request.objectKey());
        return ResponseEntity.ok().eTag(ProductView.etag(p)).body(ProductView.of(p, imageBaseUrl));
    }

    public record ConfirmRequest(@NotNull @NotBlank String objectKey) {}

    @PostMapping("/categories")
    public ResponseEntity<CategoryCreated> createCategory(@Valid @RequestBody CategoryRequest request) {
        Category c = service.createCategory(request.slug(), request.name(), request.parentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CategoryCreated(c.id(), c.parentId(), c.slug(), c.name()));
    }

    /** {@code "3"} or {@code W/"3"} → 3. */
    static long expectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank())
            throw new DomainException("PRECONDITION_REQUIRED", "Send the product's ETag in If-Match to update it");
        String v = ifMatch.trim();
        if (v.startsWith("W/")) v = v.substring(2);
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new DomainException("VERSION_MISMATCH", "If-Match " + ifMatch + " is not a version of this product");
        }
    }
}
