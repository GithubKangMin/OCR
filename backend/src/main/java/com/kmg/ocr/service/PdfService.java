package com.kmg.ocr.service;

import com.kmg.ocr.model.OcrPageResult;
import com.kmg.ocr.model.OcrWord;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class PdfService {

    public Path writeSearchablePdf(List<OcrPageResult> pages, Path outputPath) {
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("No OCR pages available.");
        }

        try {
            Files.createDirectories(outputPath.getParent());
            try (PDDocument document = new PDDocument()) {
                PDFont font = resolveFont(document);

                for (OcrPageResult pageResult : pages) {
                    BufferedImage image = ImageIO.read(pageResult.imagePath().toFile());
                    if (image == null) {
                        throw new IllegalStateException("Failed to read image: " + pageResult.imagePath());
                    }

                    float width = image.getWidth();
                    float height = image.getHeight();
                    PDPage page = new PDPage(new PDRectangle(width, height));
                    document.addPage(page);

                    PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
                    try (PDPageContentStream imageStream = new PDPageContentStream(document, page)) {
                        imageStream.drawImage(pdImage, 0, 0, width, height);
                    }

                    writeInvisibleTextLayer(document, page, pageResult.words(), pageResult.fullText(), width, height, font);
                }

                document.save(outputPath.toFile());
            }
            return outputPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write PDF: " + outputPath, e);
        }
    }

    private void writeInvisibleTextLayer(PDDocument document, PDPage page, List<OcrWord> words, String fullText,
                                         float pageWidth, float pageHeight, PDFont font) throws IOException {
        List<TextLine> lines = buildTextLines(words);
        List<String> canonicalLines = normalizeFullTextLines(fullText);

        if (lines.isEmpty()) {
            if (!canonicalLines.isEmpty()) {
                writeCanonicalTextBlockFallback(document, page, canonicalLines, words, pageWidth, pageHeight, font);
                return;
            }
            writeFallbackText(document, page, fullText, pageHeight, font);
            return;
        }

        try (PDPageContentStream textStream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            textStream.setRenderingMode(RenderingMode.NEITHER);

            for (TextLine line : lines) {
                String joined = joinLineWords(line.words());
                String safeText = filterRenderableText(font, joined);
                if (safeText.isBlank()) {
                    continue;
                }

                float x = clamp(line.minX(), 0f, pageWidth - 1f);
                float yBottom = clamp(pageHeight - line.maxY(), 0f, pageHeight - 1f);
                float fontSize = Math.max(4f, Math.min(64f, line.height()));

                writeTextChunk(textStream, font, fontSize, x, yBottom, safeText);
            }
        }
    }

    private void writeCanonicalTextBlockFallback(PDDocument document, PDPage page, List<String> canonicalLines, List<OcrWord> words,
                                                 float pageWidth, float pageHeight, PDFont font) throws IOException {
        float estimatedFontSize = estimateFontSize(words, pageHeight);
        float leading = Math.max(estimatedFontSize * 1.25f, 9f);
        float x = clamp(1f, 0f, pageWidth - 1f);
        float y = Math.max(10f, pageHeight - 10f);

        try (PDPageContentStream textStream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            textStream.setRenderingMode(RenderingMode.NEITHER);
            for (String rawLine : canonicalLines) {
                String safeText = filterRenderableText(font, rawLine);
                if (!safeText.isBlank()) {
                    writeTextChunk(textStream, font, estimatedFontSize, x, y, safeText);
                }
                y -= leading;
                if (y < 2f) {
                    break;
                }
            }
        }
    }

    private float estimateFontSize(List<OcrWord> words, float pageHeight) {
        if (words == null || words.isEmpty()) {
            return Math.max(8f, Math.min(14f, pageHeight / 120f));
        }

        List<Float> heights = words.stream()
                .map(word -> Math.max(1f, word.maxY() - word.minY()))
                .sorted()
                .toList();
        float median = heights.get(heights.size() / 2);
        return Math.max(8f, Math.min(22f, median));
    }

    private List<String> normalizeFullTextLines(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        for (String rawLine : fullText.split("\\R")) {
            String normalized = rawLine.replace('\u00A0', ' ').strip();
            if (!normalized.isBlank()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private List<TextLine> buildTextLines(List<OcrWord> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }

        List<OcrWord> filtered = words.stream()
                .filter(word -> word.text() != null && !word.text().isBlank())
                .sorted(Comparator
                        .comparingDouble((OcrWord word) -> (word.minY() + word.maxY()) / 2.0)
                        .thenComparingDouble(OcrWord::minX))
                .toList();
        if (filtered.isEmpty()) {
            return List.of();
        }

        List<Float> heights = filtered.stream()
                .map(word -> Math.max(1f, word.maxY() - word.minY()))
                .sorted()
                .toList();
        float medianHeight = heights.get(heights.size() / 2);
        float lineThreshold = Math.max(3f, medianHeight * 0.65f);

        List<TextLine> lines = new ArrayList<>();
        for (OcrWord word : filtered) {
            float centerY = (word.minY() + word.maxY()) / 2f;
            TextLine best = null;
            float bestDiff = Float.MAX_VALUE;
            for (TextLine line : lines) {
                float diff = Math.abs(centerY - line.centerY());
                if (diff <= lineThreshold && diff < bestDiff) {
                    best = line;
                    bestDiff = diff;
                }
            }
            if (best == null) {
                lines.add(TextLine.from(word));
            } else {
                best.add(word);
            }
        }

        lines.forEach(TextLine::sortByX);
        lines.sort(Comparator.comparingDouble(TextLine::centerY).thenComparingDouble(TextLine::minX));
        return lines;
    }

    private String joinLineWords(List<OcrWord> words) {
        StringBuilder sb = new StringBuilder();
        String prevToken = "";
        for (OcrWord word : words) {
            String token = word.text() == null ? "" : word.text().trim();
            if (token.isBlank()) {
                continue;
            }

            if (sb.isEmpty()) {
                sb.append(token);
                prevToken = token;
                continue;
            }

            if (startsWithPunctuation(token) || endsWithOpenPunctuation(sb) || shouldAttachKoreanParticle(prevToken, token)) {
                sb.append(token);
            } else {
                sb.append(' ').append(token);
            }
            prevToken = token;
        }
        return sb.toString();
    }

    private boolean shouldAttachKoreanParticle(String prevToken, String currentToken) {
        if (prevToken == null || prevToken.isBlank() || currentToken == null || currentToken.isBlank()) {
            return false;
        }
        if (!isHangulToken(currentToken) || currentToken.length() > 2) {
            return false;
        }
        return switch (currentToken) {
            case "은", "는", "이", "가", "을", "를", "의",
                 "에", "도", "와", "과", "로", "만", "께",
                 "랑", "나", "야", "요", "께서", "에서",
                 "에게", "부터", "까지", "처럼", "보다",
                 "으로", "라도", "이나", "이며", "인데", "이다" -> true;
            default -> false;
        };
    }

    private boolean isHangulToken(String text) {
        return text.codePoints().allMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
    }

    private boolean startsWithPunctuation(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        int cp = token.codePointAt(0);
        int type = Character.getType(cp);
        return switch (type) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> "·~%)]}>".indexOf(cp) >= 0;
        };
    }

    private boolean endsWithOpenPunctuation(StringBuilder sb) {
        if (sb.isEmpty()) {
            return false;
        }
        char last = sb.charAt(sb.length() - 1);
        return "([{<\"'“‘".indexOf(last) >= 0;
    }

    private void writeFallbackText(PDDocument document, PDPage page, String fullText,
                                   float pageHeight, PDFont font) throws IOException {
        if (fullText == null || fullText.isBlank()) {
            return;
        }

        try (PDPageContentStream textStream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            textStream.setRenderingMode(RenderingMode.NEITHER);
            textStream.beginText();
            textStream.setFont(font, 8f);
            textStream.setLeading(9f);
            textStream.newLineAtOffset(1f, Math.max(10f, pageHeight - 10f));

            for (String rawLine : fullText.split("\\R")) {
                String safe = filterRenderableText(font, rawLine);
                if (safe.isBlank()) {
                    textStream.newLine();
                    continue;
                }
                try {
                    textStream.showText(safe);
                } catch (Exception ex) {
                    String repaired = stripUnsupportedCharacters(font, safe);
                    if (!repaired.isBlank()) {
                        textStream.showText(repaired);
                    }
                }
                textStream.newLine();
            }

            textStream.endText();
        }
    }

    private String filterRenderableText(PDFont font, String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int[] codePoints = raw.codePoints().toArray();
        for (int cp : codePoints) {
            if (Character.isISOControl(cp) && !Character.isWhitespace(cp)) {
                continue;
            }
            if (font instanceof PDType0Font) {
                if (Character.isWhitespace(cp) || canRender(font, cp)) {
                    sb.appendCodePoint(cp);
                }
            } else if (cp >= 32 && cp <= 126) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private boolean canRender(PDFont font, int cp) {
        try {
            font.getStringWidth(new String(Character.toChars(cp)));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void writeTextChunk(PDPageContentStream stream, PDFont font, float fontSize, float x, float y, String text)
            throws IOException {
        if (text == null || text.isBlank()) {
            return;
        }

        String candidate = text;
        try {
            stream.beginText();
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(x, y);
            stream.showText(candidate);
            stream.endText();
        } catch (Exception ex) {
            String repaired = stripUnsupportedCharacters(font, text);
            if (repaired.isBlank()) {
                return;
            }
            stream.beginText();
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(x, y);
            stream.showText(repaired);
            stream.endText();
        }
    }

    private String stripUnsupportedCharacters(PDFont font, String raw) {
        StringBuilder sb = new StringBuilder();
        for (int cp : raw.codePoints().toArray()) {
            if (Character.isWhitespace(cp) || canRender(font, cp)) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private PDFont resolveFont(PDDocument document) {
        List<Path> candidates = List.of(
                Path.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"),
                Path.of("/System/Library/Fonts/Supplemental/Arial Unicode MS.ttf"),
                Path.of("/System/Library/Fonts/Supplemental/ArialUnicode.ttf"),
                Path.of("/System/Library/Fonts/Supplemental/AppleGothic.ttf"),
                Path.of("/Library/Fonts/AppleGothic.ttf"),
                Path.of("C:/Windows/Fonts/malgun.ttf"),
                Path.of("C:/Windows/Fonts/arialuni.ttf"),
                Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"),
                Path.of("/usr/share/fonts/truetype/nanum/NanumGothic.ttf")
        );

        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) {
                continue;
            }
            try {
                return PDType0Font.load(document, candidate.toFile());
            } catch (Exception ignored) {
            }
        }

        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class TextLine {
        private final List<OcrWord> words = new ArrayList<>();
        private float minX;
        private float minY;
        private float maxX;
        private float maxY;

        static TextLine from(OcrWord word) {
            TextLine line = new TextLine();
            line.add(word);
            return line;
        }

        void add(OcrWord word) {
            if (words.isEmpty()) {
                minX = word.minX();
                minY = word.minY();
                maxX = word.maxX();
                maxY = word.maxY();
            } else {
                minX = Math.min(minX, word.minX());
                minY = Math.min(minY, word.minY());
                maxX = Math.max(maxX, word.maxX());
                maxY = Math.max(maxY, word.maxY());
            }
            words.add(word);
        }

        void sortByX() {
            words.sort(Comparator.comparingDouble(OcrWord::minX));
        }

        List<OcrWord> words() {
            return words;
        }

        float minX() {
            return minX;
        }

        float maxY() {
            return maxY;
        }

        float height() {
            return Math.max(1f, maxY - minY);
        }

        float centerY() {
            return (minY + maxY) / 2f;
        }
    }
}
