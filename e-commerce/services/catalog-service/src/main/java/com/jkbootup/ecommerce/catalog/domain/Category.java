package com.jkbootup.ecommerce.catalog.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/** A node of the category tree; {@code parentId} is null for a top-level category. */
public record Category(UUID id, UUID parentId, String slug, String name) {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public Category {
        if (slug == null || slug.length() > 80 || !SLUG.matcher(slug).matches())
            throw new DomainException("INVALID_SLUG", "A slug is lower-case words joined by dashes, at most 80 characters");
        if (name == null || name.isBlank() || name.length() > 100)
            throw new DomainException("INVALID_NAME", "A name of 1 to 100 characters is required");
    }
}
