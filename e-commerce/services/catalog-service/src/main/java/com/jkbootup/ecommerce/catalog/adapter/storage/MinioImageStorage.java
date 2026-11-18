package com.jkbootup.ecommerce.catalog.adapter.storage;

import com.jkbootup.ecommerce.catalog.application.port.ImageStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Product images in MinIO (S3 API). Uploads never pass through this service: it signs a PUT URL valid for a few
 * minutes, the browser sends the file straight to storage, and staff then confirm the object key.
 */
public class MinioImageStorage implements ImageStorage {

    private final MinioClient client;
    private final MinioClient signer;
    private final String bucket;
    private final Clock clock;

    public MinioImageStorage(MinioClient client, String bucket, Clock clock) {
        this(client, client, bucket, clock);
    }

    /**
     * @param signer signs upload URLs for the browser. It can name a different host from {@code client} (for example
     *               localhost:9000 outside a container network while the service reaches storage as rustfs:9000). Signing
     *               is local, so the signer never needs to reach that host itself.
     */
    public MinioImageStorage(MinioClient client, MinioClient signer, String bucket, Clock clock) {
        this.client = client;
        this.signer = signer;
        this.bucket = bucket;
        this.clock = clock;
    }

    /** Creates the bucket on first start; harmless when it already exists. */
    public void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare bucket " + bucket, e);
        }
    }

    @Override
    public UploadTicket presignUpload(String objectKey, String contentType, Duration validFor) {
        try {
            String url = signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT).bucket(bucket)
                    .object(objectKey).expiry((int) validFor.toSeconds()).extraHeaders(Map.of("Content-Type", contentType)).build());
            return new UploadTicket(url, objectKey, clock.instant().plus(validFor));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign an upload URL", e);
        }
    }

    @Override
    public Optional<StoredObject> find(String objectKey) {
        try {
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return Optional.of(new StoredObject(stat.contentType(), stat.size()));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) return Optional.empty();
            throw new IllegalStateException("Object storage error for " + objectKey, e);
        } catch (Exception e) {
            throw new IllegalStateException("Object storage error for " + objectKey, e);
        }
    }
}
