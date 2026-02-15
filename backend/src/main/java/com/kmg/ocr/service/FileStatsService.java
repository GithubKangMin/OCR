package com.kmg.ocr.service;

import com.kmg.ocr.dto.FolderStatsResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class FileStatsService {
    private static final Set<String> SUPPORTED = Set.of("png", "jpg", "jpeg", "webp");

    public FolderStatsResponse computeStats(String pathStr) {
        Path path = normalizeFolderPath(pathStr);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("폴더를 찾을 수 없습니다: " + path);
        }

        int count;
        try (Stream<Path> stream = Files.walk(path)) {
            count = (int) stream.filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .count();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan folder: " + e.getMessage(), e);
        }

        return new FolderStatsResponse(path.toString(), count, count);
    }

    public List<Path> listSupportedImages(String pathStr) {
        Path path = normalizeFolderPath(pathStr);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("폴더를 찾을 수 없습니다: " + path);
        }
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list images: " + e.getMessage(), e);
        }
    }

    private boolean isSupported(Path path) {
        String name = path.getFileName().toString();
        int idx = name.lastIndexOf('.');
        if (idx < 0) {
            return false;
        }
        String ext = name.substring(idx + 1).toLowerCase();
        return SUPPORTED.contains(ext);
    }

    private Path normalizeFolderPath(String pathStr) {
        Path path = Path.of(pathStr).toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) {
            Path parent = path.getParent();
            if (parent != null && Files.isDirectory(parent)) {
                return parent;
            }
        }
        return path;
    }
}
