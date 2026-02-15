package com.kmg.ocr.service;

import com.kmg.ocr.config.OcrProperties;
import com.kmg.ocr.model.CredentialRecord;
import com.kmg.ocr.model.CredentialSummary;
import com.kmg.ocr.model.UsageRecord;
import com.kmg.ocr.repo.CredentialRepository;
import com.kmg.ocr.repo.UsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class QuotaService {
    private final CredentialRepository credentialRepository;
    private final UsageRepository usageRepository;
    private final TimeService timeService;
    private final OcrProperties properties;

    public QuotaService(
            CredentialRepository credentialRepository,
            UsageRepository usageRepository,
            TimeService timeService,
            OcrProperties properties
    ) {
        this.credentialRepository = credentialRepository;
        this.usageRepository = usageRepository;
        this.timeService = timeService;
        this.properties = properties;
    }

    public List<CredentialSummary> listCredentialSummaries() {
        String period = timeService.currentPeriod();
        int cap = properties.getCredentials().getMonthlyCap();

        List<CredentialRecord> credentials = credentialRepository.findAll();
        Map<String, UsageRecord> usageMap = usageRepository.findByPeriod(period)
                .stream()
                .collect(Collectors.toMap(UsageRecord::credentialId, Function.identity()));

        String resetAt = timeService.nextResetAt().toOffsetDateTime().toString();

        List<CredentialSummary> result = new ArrayList<>();
        for (CredentialRecord credential : credentials) {
            UsageRecord usage = usageMap.computeIfAbsent(
                    credential.id(),
                    id -> usageRepository.ensureRow(id, period, cap)
            );

            int remaining = Math.max(0, usage.capUnits() - usage.usedUnits());
            String status = credential.active() && Files.exists(Path.of(credential.filePath())) ? "ACTIVE" : "MISSING";

            result.add(new CredentialSummary(
                    credential.id(),
                    credential.fileName(),
                    credential.filePath(),
                    credential.accountLabel(),
                    credential.projectId(),
                    credential.serviceAccountEmail(),
                    usage.capUnits(),
                    usage.usedUnits(),
                    remaining,
                    period,
                    resetAt,
                    status
            ));
        }
        return result;
    }

    public Optional<CredentialSummary> findSummaryById(String id) {
        return listCredentialSummaries().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    @Transactional
    public CredentialSummary adjustUsage(String credentialId, int newUsed, String reason) {
        String period = timeService.currentPeriod();
        int cap = properties.getCredentials().getMonthlyCap();
        UsageRecord old = usageRepository.ensureRow(credentialId, period, cap);
        UsageRecord updated = usageRepository.setUsed(credentialId, period, cap, newUsed);
        usageRepository.addAudit(credentialId, period, old.usedUnits(), updated.usedUnits(), reason);
        return findSummaryById(credentialId).orElseThrow(() -> new IllegalArgumentException("Credential not found"));
    }

    @Transactional
    public int consumeOneUnit(String credentialId) {
        String period = timeService.currentPeriod();
        int cap = properties.getCredentials().getMonthlyCap();
        UsageRecord updated = usageRepository.incrementUsed(credentialId, period, cap, 1);
        return Math.max(0, updated.capUnits() - updated.usedUnits());
    }

    @Transactional
    public int releaseOneUnit(String credentialId) {
        String period = timeService.currentPeriod();
        int cap = properties.getCredentials().getMonthlyCap();
        UsageRecord current = usageRepository.ensureRow(credentialId, period, cap);
        int nextUsed = Math.max(0, current.usedUnits() - 1);
        UsageRecord updated = usageRepository.setUsed(credentialId, period, cap, nextUsed);
        return Math.max(0, updated.capUnits() - updated.usedUnits());
    }

    @Transactional
    public void markExhausted(String credentialId, String reason) {
        String period = timeService.currentPeriod();
        int cap = properties.getCredentials().getMonthlyCap();
        UsageRecord existing = usageRepository.ensureRow(credentialId, period, cap);
        if (existing.usedUnits() >= cap) {
            return;
        }
        usageRepository.setUsed(credentialId, period, cap, cap);
        usageRepository.addAudit(credentialId, period, existing.usedUnits(), cap, reason);
    }
}
