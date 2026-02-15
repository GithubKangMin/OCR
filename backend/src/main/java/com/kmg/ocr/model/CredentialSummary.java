package com.kmg.ocr.model;

public record CredentialSummary(
        String id,
        String fileName,
        String filePath,
        String accountLabel,
        String projectId,
        String serviceAccountEmail,
        int capUnits,
        int usedUnits,
        int remainingUnits,
        String periodPt,
        String resetAt,
        String status
) {
}
