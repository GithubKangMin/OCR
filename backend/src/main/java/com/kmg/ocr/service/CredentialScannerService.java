package com.kmg.ocr.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmg.ocr.config.OcrProperties;
import com.kmg.ocr.model.CredentialRecord;
import com.kmg.ocr.repo.CredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class CredentialScannerService {
    private static final Logger log = LoggerFactory.getLogger(CredentialScannerService.class);

    private final OcrProperties properties;
    private final CredentialRepository credentialRepository;
    private final ApplicationArguments applicationArguments;
    private final ObjectMapper objectMapper;

    public CredentialScannerService(
            OcrProperties properties,
            CredentialRepository credentialRepository,
            ApplicationArguments applicationArguments,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.credentialRepository = credentialRepository;
        this.applicationArguments = applicationArguments;
        this.objectMapper = objectMapper;
    }

    public synchronized void scanAndSync() {
        try {
            migrateRootJsonFiles();
            Set<Path> scanDirs = resolveScanDirs();
            List<String> activeFingerprints = new ArrayList<>();

            for (Path dir : scanDirs) {
                if (!Files.exists(dir)) {
                    continue;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                    for (Path file : stream) {
                        Optional<CredentialRecord> maybeRecord = toCredentialRecord(file);
                        if (maybeRecord.isEmpty()) {
                            continue;
                        }
                        CredentialRecord record = maybeRecord.get();
                        credentialRepository.upsert(record);
                        activeFingerprints.add(record.fingerprint());
                    }
                }
            }

            credentialRepository.setActiveByKnownFingerprints(activeFingerprints);
            log.info("Credential scan complete. active={}", activeFingerprints.size());
        } catch (Exception e) {
            log.error("Credential scan failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to scan credentials", e);
        }
    }

    private Set<Path> resolveScanDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        dirs.add(Path.of(properties.getCredentials().getDefaultDir()));

        List<String> cliDirs = applicationArguments.getOptionValues("credentials-dir");
        if (cliDirs != null) {
            cliDirs.stream()
                    .filter(Objects::nonNull)
                    .flatMap(v -> Arrays.stream(v.split(",")))
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .map(Path::of)
                    .forEach(dirs::add);
        }

        return dirs;
    }

    private void migrateRootJsonFiles() throws IOException {
        Path baseDir = Path.of(properties.getBaseDir());
        Path credentialsDir = Path.of(properties.getCredentials().getDefaultDir());
        Files.createDirectories(credentialsDir);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "*.json")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                Optional<Map<String, Object>> payload = readJson(file);
                if (payload.isEmpty() || !isServiceAccount(payload.get())) {
                    continue;
                }

                Path destination = credentialsDir.resolve(file.getFileName());
                if (Files.exists(destination)) {
                    String name = file.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    String base = dot >= 0 ? name.substring(0, dot) : name;
                    String ext = dot >= 0 ? name.substring(dot) : "";
                    destination = credentialsDir.resolve(base + "_" + System.currentTimeMillis() + ext);
                }

                Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
                log.info("Moved credential json from root: {} -> {}", file, destination);
            }
        }
    }

    private Optional<CredentialRecord> toCredentialRecord(Path file) {
        Optional<Map<String, Object>> payloadOpt = readJson(file);
        if (payloadOpt.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> payload = payloadOpt.get();
        if (!isServiceAccount(payload)) {
            return Optional.empty();
        }

        String projectId = Objects.toString(payload.getOrDefault("project_id", ""), "");
        String email = Objects.toString(payload.getOrDefault("client_email", ""), "");
        String privateKeyId = Objects.toString(payload.getOrDefault("private_key_id", ""), "");
        String accountLabel = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;

        if (projectId.isBlank() || email.isBlank()) {
            return Optional.empty();
        }

        String fingerprint = sha256(projectId + "|" + email + "|" + privateKeyId);
        String id = credentialRepository.findByFingerprint(fingerprint)
                .map(CredentialRecord::id)
                .orElse(UUID.randomUUID().toString());

        return Optional.of(new CredentialRecord(
                id,
                fingerprint,
                file.toAbsolutePath().toString(),
                file.getFileName().toString(),
                accountLabel,
                projectId,
                email,
                privateKeyId,
                true,
                null,
                null
        ));
    }

    private Optional<Map<String, Object>> readJson(Path file) {
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), new TypeReference<>() {
            }));
        } catch (Exception e) {
            log.warn("Invalid JSON skipped: {} ({})", file, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isServiceAccount(Map<String, Object> payload) {
        Object type = payload.get("type");
        return "service_account".equals(type);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash credential fingerprint", e);
        }
    }
}
