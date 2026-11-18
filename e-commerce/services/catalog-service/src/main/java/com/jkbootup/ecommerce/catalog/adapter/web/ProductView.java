package com.jkbootup.ecommerce.catalog.adapter.web;

import com.jkbootup.ecommerce.catalog.domain.Product;
import com.jkbootup.ecommerce.catalog.domain.ProductDetails;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Product resource of the API catalogue. Image URLs point at the public image host (a CDN in production). */
public record ProductView(UUID id, String sku, String name, String description, UUID categoryId, String brand, MoneyView price,
                          Map<String, Object> attributes, String status, List<ImageView> images, long version) {

    public record MoneyView(String amount, String currency) {}

    public record ImageView(String url, int position) {}

    static ProductView of(Product p, String imageBaseUrl) {
        ProductDetails d = p.details();
        return new ProductView(p.id(), p.sku(), d.name(), d.description(), d.categoryId(), d.brand(),
                new MoneyView(d.price().amount().toPlainString(), d.price().currency().getCurrencyCode()), d.attributes(),
                d.status().name(), p.images().stream().map(i -> new ImageView(imageBaseUrl + "/" + i.objectKey(), i.position())).toList(),
                p.version());
    }

    /** Strong entity tag: the version in quotes. */
    static String etag(Product p) {
        return "\"" + p.version() + "\"";
    }
}
