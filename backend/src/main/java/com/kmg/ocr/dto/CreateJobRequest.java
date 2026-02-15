package com.kmg.ocr.dto;

import com.kmg.ocr.model.KeySelectionStrategy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateJobRequest(
        @NotEmpty List<String> folders,
        @NotNull KeySelectionStrategy strategy,
        @Min(1) @Max(8) int parallelism
) {
}
