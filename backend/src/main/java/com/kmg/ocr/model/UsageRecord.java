package com.kmg.ocr.model;

import java.time.OffsetDateTime;

public record UsageRecord(
        String credentialId,
        String periodPt,
        int capUnits,
        int usedUnits,
        int adjustedUnits,
        OffsetDateTime updatedAt
) {
}
