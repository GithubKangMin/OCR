package com.kmg.ocr.service;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import com.kmg.ocr.model.OcrPageResult;
import com.kmg.ocr.model.OcrWord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OcrService {
    private final Map<String, ImageAnnotatorClient> clients = new ConcurrentHashMap<>();

    public OcrPageResult detectText(Path imagePath, Path credentialPath) {
        try {
            ImageAnnotatorClient client = getOrCreateClient(credentialPath);
            ByteString content = ByteString.readFrom(Files.newInputStream(imagePath));

            Image image = Image.newBuilder().setContent(content).build();
            Feature feature = Feature.newBuilder().setType(Feature.Type.DOCUMENT_TEXT_DETECTION).build();
            ImageContext context = ImageContext.newBuilder()
                    .addLanguageHints("ko")
                    .addLanguageHints("en")
                    .build();

            AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                    .setImage(image)
                    .addFeatures(feature)
                    .setImageContext(context)
                    .build();

            BatchAnnotateImagesResponse batchResponse = client.batchAnnotateImages(List.of(request));
            AnnotateImageResponse response = batchResponse.getResponses(0);

            if (response.hasError()) {
                String message = response.getError().getMessage();
                if (isQuotaMessage(message)) {
                    throw new OcrQuotaExceededException(message);
                }
                throw new OcrFailedException(message);
            }

            String fullText = "";
            if (response.hasFullTextAnnotation()) {
                fullText = response.getFullTextAnnotation().getText();
            } else if (!response.getTextAnnotationsList().isEmpty()) {
                fullText = response.getTextAnnotationsList().get(0).getDescription();
            }

            List<OcrWord> words = extractWords(response.getFullTextAnnotation());
            return new OcrPageResult(imagePath, fullText, words);
        } catch (OcrQuotaExceededException | OcrFailedException e) {
            throw e;
        } catch (ApiException e) {
            if (e.getStatusCode() != null && e.getStatusCode().getCode() != null
                    && "RESOURCE_EXHAUSTED".equals(e.getStatusCode().getCode().name())) {
                throw new OcrQuotaExceededException(e.getMessage());
            }
            throw new OcrFailedException(e.getMessage(), e);
        } catch (IOException e) {
            throw new OcrFailedException("Failed to read image: " + imagePath, e);
        }
    }

    private ImageAnnotatorClient getOrCreateClient(Path credentialPath) throws IOException {
        String key = credentialPath.toAbsolutePath().normalize().toString();
        ImageAnnotatorClient existing = clients.get(key);
        if (existing != null) {
            return existing;
        }

        GoogleCredentials credentials = ServiceAccountCredentials.fromStream(Files.newInputStream(credentialPath));
        ImageAnnotatorSettings settings = ImageAnnotatorSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();
        ImageAnnotatorClient created = ImageAnnotatorClient.create(settings);
        clients.put(key, created);
        return created;
    }

    private boolean isQuotaMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("resource_exhausted") || lower.contains("quota") || lower.contains("rate limit");
    }

    private List<OcrWord> extractWords(TextAnnotation annotation) {
        List<OcrWord> words = new ArrayList<>();
        if (annotation == null) {
            return words;
        }

        for (Page page : annotation.getPagesList()) {
            for (Block block : page.getBlocksList()) {
                for (Paragraph paragraph : block.getParagraphsList()) {
                    for (Word word : paragraph.getWordsList()) {
                        StringBuilder sb = new StringBuilder();
                        for (Symbol symbol : word.getSymbolsList()) {
                            sb.append(symbol.getText());
                        }
                        if (sb.isEmpty()) {
                            continue;
                        }

                        BoundingPoly poly = word.getBoundingBox();
                        if (poly == null || poly.getVerticesCount() == 0) {
                            continue;
                        }

                        float minX = Float.MAX_VALUE;
                        float minY = Float.MAX_VALUE;
                        float maxX = Float.MIN_VALUE;
                        float maxY = Float.MIN_VALUE;

                        for (Vertex vertex : poly.getVerticesList()) {
                            float x = vertex.getX();
                            float y = vertex.getY();
                            minX = Math.min(minX, x);
                            minY = Math.min(minY, y);
                            maxX = Math.max(maxX, x);
                            maxY = Math.max(maxY, y);
                        }

                        if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE) {
                            continue;
                        }

                        words.add(new OcrWord(sb.toString(), minX, minY, maxX, maxY));
                    }
                }
            }
        }

        return words;
    }

    public static class OcrQuotaExceededException extends RuntimeException {
        public OcrQuotaExceededException(String message) {
            super(message);
        }
    }

    public static class OcrFailedException extends RuntimeException {
        public OcrFailedException(String message) {
            super(message);
        }

        public OcrFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
