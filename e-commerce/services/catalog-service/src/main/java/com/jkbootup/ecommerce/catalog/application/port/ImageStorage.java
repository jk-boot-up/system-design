package com.jkbootup.ecommerce.catalog.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Object storage for product images. The browser uploads straight to it with a presigned URL. */
public interface ImageStorage {

    record UploadTicket(String uploadUrl, String objectKey, Instant expiresAt) {}

    record StoredObject(String contentType, long sizeBytes) {}

    UploadTicket presignUpload(String objectKey, String contentType, Duration validFor);

    Optional<StoredObject> find(String objectKey);
}
