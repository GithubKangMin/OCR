package com.kmg.ocr.dto;

public record PickFolderResponse(String path, boolean cancelled, String message) {
}
