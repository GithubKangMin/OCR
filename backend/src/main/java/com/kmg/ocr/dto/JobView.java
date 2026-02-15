package com.kmg.ocr.dto;

import com.kmg.ocr.model.JobStatus;
import com.kmg.ocr.model.KeySelectionStrategy;

import java.util.List;

public record JobView(
        String id,
        KeySelectionStrategy strategy,
        int parallelism,
        JobStatus status,
        String createdAt,
        String startedAt,
        String endedAt,
        String stopReason,
        int totalItems,
        int processedItems,
        String currentCredentialId,
        String lastError,
        List<JobItemView> items
) {
}
