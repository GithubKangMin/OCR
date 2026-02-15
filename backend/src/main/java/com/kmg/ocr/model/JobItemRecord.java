package com.kmg.ocr.model;

import java.time.OffsetDateTime;

public record JobItemRecord(
        String id,
        String jobId,
        int queueIndex,
        String folderPath,
        int imageTotal,
        int imageDone,
        JobItemStatus status,
        String pdfPath,
        String errorReason,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt
) {
}
