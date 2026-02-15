package com.kmg.ocr.model;

public record OcrWord(String text, float minX, float minY, float maxX, float maxY) {
}
