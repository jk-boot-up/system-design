package com.jkbootup.ecommerce.catalog.adapter.web;

import com.jkbootup.ecommerce.catalog.application.CatalogService;
import com.jkbootup.ecommerce.catalog.domain.Product;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Public, read-only catalogue API. Only ACTIVE products exist here. */
@RestController
@RequestMapping("/api/v1")
public class ProductController {

    public record CategoryView(UUID id, String slug, String name, List<CategoryView> children) {
        static CategoryView of(CatalogService.CategoryNode n) {
            return new CategoryView(n.category().id(), n.category().slug(), n.category().name(),
                    n.children().stream().map(CategoryView::of).toList());
        }
    }

    private final CatalogService service;
    private final String imageBaseUrl;

    public ProductController(CatalogService service, ImageLinks links) {
        this.service = service;
        this.imageBaseUrl = links.baseUrl();
    }

    /** A few products by SKU, for pages that only know SKUs (cart lines, recommendations). */
    @GetMapping("/products/by-sku")
    public ResponseEntity<List<ProductView>> bySku(@RequestParam("sku") List<String> skus) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(service.activeBySkus(skus).stream().map(p -> ProductView.of(p, imageBaseUrl)).toList());
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ProductView> product(@PathVariable UUID id) {
        Product p = service.activeProduct(id);
        return ResponseEntity.ok().eTag(ProductView.etag(p)).cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(ProductView.of(p, imageBaseUrl));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryView>> categories() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(service.categoryTree().stream().map(CategoryView::of).toList());
    }
}
