package com.kmg.ocr.model;

import java.nio.file.Path;
import java.util.List;

public record OcrPageResult(Path imagePath, String fullText, List<OcrWord> words) {
}
