/*
 *
 *     Copyright 2018 Expedia, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 *
 */
package com.expedia.blobs.stores.aws;

import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;
import com.expedia.blobs.core.Blob;
import com.expedia.blobs.core.BlobReadWriteException;
import com.expedia.blobs.core.SimpleBlob;
import com.expedia.blobs.core.io.AsyncSupport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class S3BlobStore extends AsyncSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(S3BlobStore.class);
    private final String bucketName;
    private final TransferManager transferManager;
    private Thread shutdownHook = new Thread(() -> this.close());

    @VisibleForTesting
    Boolean shutdownHookAdded = false;

    S3BlobStore(S3BlobStore.Builder builder) {
        super(builder.threadPoolSize, builder.shutdownWaitInSeconds);

        this.transferManager = builder.transferManager;
        this.bucketName = builder.bucketName;

        if (builder.closeOnShutdown) {
            this.shutdownHookAdded = builder.closeOnShutdown;
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
        } else {
            LOGGER.info("No shutdown hook registered: Please call close() manually on application shutdown.");
        }
    }


    @Override
    protected void storeInternal(Blob blob) {
        try {
            final ObjectMetadata metadata = new ObjectMetadata();
            blob.getMetadata().forEach(metadata::addUserMetadata);

            final InputStream stream = new ByteArrayInputStream(blob.getData());
            metadata.setContentLength(blob.getSize());

            final PutObjectRequest putRequest =
                    new PutObjectRequest(bucketName, blob.getKey(), stream, metadata)
                            .withCannedAcl(CannedAccessControlList.BucketOwnerFullControl)
                            .withGeneralProgressListener(new UploadProgressListener(LOGGER, blob.getKey()));

            final Upload upload = transferManager.upload(putRequest);
            //upload.waitForUploadResult();
        } catch (Exception e) {
            final String message = String.format("Unable to upload blob to S3 for  key %s : %s",
                    blob.getKey(),
                    e.getMessage());
            throw new BlobReadWriteException(message, e);
        }
    }

    @Override
    protected Optional<Blob> readInternal(String key) {
        try {
            final S3Object s3Object = transferManager.getAmazonS3Client().getObject(bucketName, key);
            final Map<String, String> objectMetadata = s3Object.getObjectMetadata().getUserMetadata();
            try (final InputStream is = s3Object.getObjectContent()) {
                return Optional.of(new SimpleBlob(key,
                        objectMetadata == null ? new HashMap<>(0) : objectMetadata,
                        readInputStream(is)));
            }
        } catch (Exception e) {
            throw new BlobReadWriteException(e);
        }
    }

    protected byte[] readInputStream(InputStream is) throws IOException {
        return IOUtils.toByteArray(is);
    }

    @Override
    public void close() {
        super.close();
        this.transferManager.shutdownNow();
    }

    /**
     * Builds the {@link S3BlobStore} with options
     */
    public static class Builder {
        private final String bucketName;
        private final TransferManager transferManager;
        private int threadPoolSize;
        private int shutdownWaitInSeconds;
        private boolean closeOnShutdown;

        public Builder(String bucketName, TransferManager transferManager) {
            this.bucketName = bucketName;
            this.transferManager = transferManager;
            this.threadPoolSize = Runtime.getRuntime().availableProcessors();
            this.shutdownWaitInSeconds = 60;
            this.closeOnShutdown = true;
        }

        /**
         * @param threadPoolSize 1 or more
         * @return {@link S3BlobStore.Builder}
         */

        public Builder withThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
            return this;
        }

        public Builder withShutdownWaitInSeconds(int shutdownWaitInSeconds) {
            this.shutdownWaitInSeconds = shutdownWaitInSeconds;
            return this;
        }

        public Builder disableAutoShutdown() {
            this.closeOnShutdown = false;
            return this;
        }


        public S3BlobStore build() {

            Validate.notNull(transferManager);
            Validate.notEmpty(bucketName);
            return new S3BlobStore(this);
        }
    }
}
