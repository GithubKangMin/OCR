package com.kmg.ocr.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Validated
@ConfigurationProperties(prefix = "ocr")
public class OcrProperties {
    @NotBlank
    private String baseDir;
    @NotNull
    private Browser browser = new Browser();
    @NotNull
    private Credentials credentials = new Credentials();
    @NotNull
    private Output output = new Output();
    @NotNull
    private State state = new State();
    @NotNull
    private Logs logs = new Logs();
    @NotNull
    private ExternalLinks externalLinks = new ExternalLinks();

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public Browser getBrowser() {
        return browser;
    }

    public void setBrowser(Browser browser) {
        this.browser = browser;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    public Output getOutput() {
        return output;
    }

    public void setOutput(Output output) {
        this.output = output;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Logs getLogs() {
        return logs;
    }

    public void setLogs(Logs logs) {
        this.logs = logs;
    }

    public ExternalLinks getExternalLinks() {
        return externalLinks;
    }

    public void setExternalLinks(ExternalLinks externalLinks) {
        this.externalLinks = externalLinks;
    }

    public Path baseDirPath() {
        return Path.of(baseDir);
    }

    public static class Browser {
        private boolean autoOpen = true;

        public boolean isAutoOpen() {
            return autoOpen;
        }

        public void setAutoOpen(boolean autoOpen) {
            this.autoOpen = autoOpen;
        }
    }

    public static class Credentials {
        @NotBlank
        private String defaultDir;
        private int monthlyCap = 1000;
        @NotBlank
        private String timezone = "America/Los_Angeles";

        public String getDefaultDir() {
            return defaultDir;
        }

        public void setDefaultDir(String defaultDir) {
            this.defaultDir = defaultDir;
        }

        public int getMonthlyCap() {
            return monthlyCap;
        }

        public void setMonthlyCap(int monthlyCap) {
            this.monthlyCap = monthlyCap;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }
    }

    public static class Output {
        @NotBlank
        private String pdfDir;
        @NotBlank
        private String reportDir;

        public String getPdfDir() {
            return pdfDir;
        }

        public void setPdfDir(String pdfDir) {
            this.pdfDir = pdfDir;
        }

        public String getReportDir() {
            return reportDir;
        }

        public void setReportDir(String reportDir) {
            this.reportDir = reportDir;
        }
    }

    public static class State {
        @NotBlank
        private String dbPath;

        public String getDbPath() {
            return dbPath;
        }

        public void setDbPath(String dbPath) {
            this.dbPath = dbPath;
        }
    }

    public static class Logs {
        @NotBlank
        private String dir;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    public static class ExternalLinks {
        @NotBlank
        private String keyCreationUrl;
        @NotBlank
        private String keyMonitoringUrl;

        public String getKeyCreationUrl() {
            return keyCreationUrl;
        }

        public void setKeyCreationUrl(String keyCreationUrl) {
            this.keyCreationUrl = keyCreationUrl;
        }

        public String getKeyMonitoringUrl() {
            return keyMonitoringUrl;
        }

        public void setKeyMonitoringUrl(String keyMonitoringUrl) {
            this.keyMonitoringUrl = keyMonitoringUrl;
        }
    }
}
