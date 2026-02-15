package com.kmg.ocr.service;

import com.kmg.ocr.config.OcrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.net.URI;

@Service
public class BrowserLauncher {
    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final OcrProperties properties;

    public BrowserLauncher(OcrProperties properties) {
        this.properties = properties;
    }

    public void openIfEnabled(int port) {
        if (!properties.getBrowser().isAutoOpen()) {
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Headless environment detected; browser auto-open skipped.");
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            log.info("Desktop API is not supported; browser auto-open skipped.");
            return;
        }

        try {
            Desktop.getDesktop().browse(new URI("http://localhost:" + port));
        } catch (Exception e) {
            log.warn("Failed to open browser automatically: {}", e.getMessage());
        }
    }
}
