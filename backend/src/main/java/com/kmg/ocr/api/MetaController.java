package com.kmg.ocr.api;

import com.kmg.ocr.config.OcrProperties;
import com.kmg.ocr.dto.ExternalLinksResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meta")
public class MetaController {
    private final OcrProperties properties;

    public MetaController(OcrProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/external-links")
    public ExternalLinksResponse externalLinks() {
        return new ExternalLinksResponse(
                properties.getExternalLinks().getKeyCreationUrl(),
                properties.getExternalLinks().getKeyMonitoringUrl()
        );
    }
}
