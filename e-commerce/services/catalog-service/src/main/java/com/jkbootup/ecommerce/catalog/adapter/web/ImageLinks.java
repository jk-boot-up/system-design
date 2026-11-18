package com.jkbootup.ecommerce.catalog.adapter.web;

/** Where shoppers' browsers fetch product images from: the CDN in production, MinIO's public bucket locally. */
public record ImageLinks(String baseUrl) {
}
