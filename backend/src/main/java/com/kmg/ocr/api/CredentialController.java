package com.kmg.ocr.api;

import com.kmg.ocr.dto.UsageAdjustmentRequest;
import com.kmg.ocr.model.CredentialSummary;
import com.kmg.ocr.service.CredentialScannerService;
import com.kmg.ocr.service.QuotaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/credentials")
public class CredentialController {
    private final CredentialScannerService credentialScannerService;
    private final QuotaService quotaService;

    public CredentialController(CredentialScannerService credentialScannerService, QuotaService quotaService) {
        this.credentialScannerService = credentialScannerService;
        this.quotaService = quotaService;
    }

    @GetMapping
    public List<CredentialSummary> list() {
        return quotaService.listCredentialSummaries();
    }

    @PostMapping("/scan")
    public List<CredentialSummary> scan() {
        credentialScannerService.scanAndSync();
        return quotaService.listCredentialSummaries();
    }

    @PatchMapping("/{id}/usage")
    public ResponseEntity<CredentialSummary> adjustUsage(
            @PathVariable String id,
            @Valid @RequestBody UsageAdjustmentRequest request
    ) {
        CredentialSummary updated = quotaService.adjustUsage(id, request.usedOverride(), request.reason());
        return ResponseEntity.ok(updated);
    }
}
