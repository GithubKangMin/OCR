package com.kmg.ocr.config;

import com.kmg.ocr.service.BrowserLauncher;
import com.kmg.ocr.service.CredentialScannerService;
import com.kmg.ocr.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class StartupInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final OcrProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final CredentialScannerService credentialScannerService;
    private final BrowserLauncher browserLauncher;
    private final JobRepository jobRepository;

    @Value("${server.port:8787}")
    private int serverPort;

    public StartupInitializer(
            OcrProperties properties,
            JdbcTemplate jdbcTemplate,
            CredentialScannerService credentialScannerService,
            BrowserLauncher browserLauncher,
            JobRepository jobRepository
    ) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.credentialScannerService = credentialScannerService;
        this.browserLauncher = browserLauncher;
        this.jobRepository = jobRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        createDirectories();
        initializeSchema();
        jobRepository.recoverRunningJobsAfterRestart();
        credentialScannerService.scanAndSync();
        browserLauncher.openIfEnabled(serverPort);
    }

    private void createDirectories() throws IOException {
        Files.createDirectories(Path.of(properties.getBaseDir()));
        Files.createDirectories(Path.of(properties.getCredentials().getDefaultDir()));
        Files.createDirectories(Path.of(properties.getOutput().getPdfDir()));
        Files.createDirectories(Path.of(properties.getOutput().getReportDir()));
        Files.createDirectories(Path.of(properties.getLogs().getDir()));
        Path dbPath = Path.of(properties.getState().getDbPath());
        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
        }
    }

    private void initializeSchema() {
        configureSqlitePragmas();

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS credentials (
              id TEXT PRIMARY KEY,
              fingerprint TEXT UNIQUE NOT NULL,
              file_path TEXT NOT NULL,
              file_name TEXT NOT NULL,
              account_label TEXT,
              project_id TEXT,
              service_account_email TEXT,
              private_key_id TEXT,
              is_active INTEGER NOT NULL DEFAULT 1,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS usage_monthly (
              credential_id TEXT NOT NULL,
              period_pt TEXT NOT NULL,
              cap_units INTEGER NOT NULL,
              used_units INTEGER NOT NULL,
              adjusted_units INTEGER NOT NULL DEFAULT 0,
              updated_at TEXT NOT NULL,
              PRIMARY KEY (credential_id, period_pt),
              FOREIGN KEY (credential_id) REFERENCES credentials(id)
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS usage_audit (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              credential_id TEXT NOT NULL,
              period_pt TEXT NOT NULL,
              old_used_units INTEGER NOT NULL,
              new_used_units INTEGER NOT NULL,
              reason TEXT NOT NULL,
              created_at TEXT NOT NULL,
              FOREIGN KEY (credential_id) REFERENCES credentials(id)
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS jobs (
              id TEXT PRIMARY KEY,
              strategy TEXT NOT NULL,
              parallelism INTEGER NOT NULL DEFAULT 2,
              status TEXT NOT NULL,
              created_at TEXT NOT NULL,
              started_at TEXT,
              ended_at TEXT,
              stop_reason TEXT,
              total_items INTEGER NOT NULL,
              processed_items INTEGER NOT NULL DEFAULT 0,
              current_credential_id TEXT,
              last_error TEXT
            )
            """);

        try {
            jdbcTemplate.execute("ALTER TABLE jobs ADD COLUMN parallelism INTEGER NOT NULL DEFAULT 2");
        } catch (Exception ignored) {
            // Column already exists.
        }

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS job_items (
              id TEXT PRIMARY KEY,
              job_id TEXT NOT NULL,
              queue_index INTEGER NOT NULL,
              folder_path TEXT NOT NULL,
              image_total INTEGER NOT NULL,
              image_done INTEGER NOT NULL DEFAULT 0,
              status TEXT NOT NULL,
              pdf_path TEXT,
              error_reason TEXT,
              created_at TEXT NOT NULL,
              started_at TEXT,
              ended_at TEXT,
              FOREIGN KEY (job_id) REFERENCES jobs(id)
            )
            """);

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS ocr_page_checkpoints (
              job_item_id TEXT NOT NULL,
              page_index INTEGER NOT NULL,
              image_path TEXT NOT NULL,
              full_text TEXT,
              words_json TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              PRIMARY KEY (job_item_id, page_index),
              FOREIGN KEY (job_item_id) REFERENCES job_items(id)
            )
            """);
    }

    private void configureSqlitePragmas() {
        try {
            jdbcTemplate.queryForObject("PRAGMA journal_mode=WAL", String.class);
            jdbcTemplate.execute("PRAGMA synchronous=NORMAL");
            jdbcTemplate.execute("PRAGMA foreign_keys=ON");
            jdbcTemplate.execute("PRAGMA busy_timeout=30000");
        } catch (Exception e) {
            log.warn("Failed to configure SQLite pragmas: {}", e.getMessage());
        }
    }
}
