package com.kmg.ocr.dto;

import com.kmg.ocr.model.JobItemStatus;

public record JobItemView(
        String id,
        int queueIndex,
        String folderPath,
        int imageTotal,
        int imageDone,
        JobItemStatus status,
        String pdfPath,
        String errorReason,
        String startedAt,
        String endedAt
) {
}
