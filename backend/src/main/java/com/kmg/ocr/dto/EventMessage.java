package com.kmg.ocr.dto;

public record EventMessage(String type, String jobId, String message, String timestamp, Object payload) {
}
