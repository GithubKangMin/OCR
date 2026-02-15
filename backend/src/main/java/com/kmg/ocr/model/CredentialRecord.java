package com.kmg.ocr.model;

import java.time.OffsetDateTime;

public record CredentialRecord(
        String id,
        String fingerprint,
        String filePath,
        String fileName,
        String accountLabel,
        String projectId,
        String serviceAccountEmail,
        String privateKeyId,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
