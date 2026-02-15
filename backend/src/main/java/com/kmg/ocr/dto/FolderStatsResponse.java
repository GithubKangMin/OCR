package com.kmg.ocr.dto;

public record FolderStatsResponse(String path, int imageCount, int supportedCount) {
}
