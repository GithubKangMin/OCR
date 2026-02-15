package com.kmg.ocr.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record UsageAdjustmentRequest(
        @Min(0) int usedOverride,
        @NotBlank String reason
) {
}
