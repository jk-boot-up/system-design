package com.jkbootup.ecommerce.catalog.domain;

/** An image stored in object storage. {@code position} starts at 1 and orders the gallery. */
public record ProductImage(int position, String objectKey, String contentType) {
}
