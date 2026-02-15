package com.kmg.ocr.model;

import java.time.OffsetDateTime;

public record JobRecord(
        String id,
        KeySelectionStrategy strategy,
        int parallelism,
        JobStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String stopReason,
        int totalItems,
        int processedItems,
        String currentCredentialId,
        String lastError
) {
}
