package com.kmg.ocr.repo;

import com.kmg.ocr.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JobRepository {
    private final JdbcTemplate jdbcTemplate;

    public JobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<JobRecord> JOB_MAPPER = new RowMapper<>() {
        @Override
        public JobRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new JobRecord(
                    rs.getString("id"),
                    KeySelectionStrategy.valueOf(rs.getString("strategy")),
                    rs.getInt("parallelism"),
                    JobStatus.valueOf(rs.getString("status")),
                    SqlTime.parse(rs.getString("created_at")),
                    SqlTime.parse(rs.getString("started_at")),
                    SqlTime.parse(rs.getString("ended_at")),
                    rs.getString("stop_reason"),
                    rs.getInt("total_items"),
                    rs.getInt("processed_items"),
                    rs.getString("current_credential_id"),
                    rs.getString("last_error")
            );
        }
    };

    private static final RowMapper<JobItemRecord> ITEM_MAPPER = new RowMapper<>() {
        @Override
        public JobItemRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new JobItemRecord(
                    rs.getString("id"),
                    rs.getString("job_id"),
                    rs.getInt("queue_index"),
                    rs.getString("folder_path"),
                    rs.getInt("image_total"),
                    rs.getInt("image_done"),
                    JobItemStatus.valueOf(rs.getString("status")),
                    rs.getString("pdf_path"),
                    rs.getString("error_reason"),
                    SqlTime.parse(rs.getString("created_at")),
                    SqlTime.parse(rs.getString("started_at")),
                    SqlTime.parse(rs.getString("ended_at"))
            );
        }
    };

    public void insertJob(JobRecord record) {
        jdbcTemplate.update(
                """
                INSERT INTO jobs(id, strategy, status, created_at, started_at, ended_at, stop_reason,
                                 total_items, processed_items, current_credential_id, last_error, parallelism)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(),
                record.strategy().name(),
                record.status().name(),
                record.createdAt().toString(),
                toText(record.startedAt()),
                toText(record.endedAt()),
                record.stopReason(),
                record.totalItems(),
                record.processedItems(),
                record.currentCredentialId(),
                record.lastError(),
                record.parallelism()
        );
    }

    public void insertItem(JobItemRecord item) {
        jdbcTemplate.update(
                """
                INSERT INTO job_items(id, job_id, queue_index, folder_path, image_total, image_done, status,
                                      pdf_path, error_reason, created_at, started_at, ended_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                item.id(),
                item.jobId(),
                item.queueIndex(),
                item.folderPath(),
                item.imageTotal(),
                item.imageDone(),
                item.status().name(),
                item.pdfPath(),
                item.errorReason(),
                item.createdAt().toString(),
                toText(item.startedAt()),
                toText(item.endedAt())
        );
    }

    public void prepareJobForRun(String jobId, Integer processedItems) {
        jdbcTemplate.update(
                """
                UPDATE jobs
                   SET status = 'RUNNING',
                       stop_reason = NULL,
                       last_error = NULL,
                       current_credential_id = NULL,
                       processed_items = COALESCE(?, processed_items),
                       started_at = ?,
                       ended_at = NULL
                 WHERE id = ?
                """,
                processedItems,
                SqlTime.nowText(),
                jobId
        );
    }

    public Optional<JobRecord> findJobById(String id) {
        List<JobRecord> rows = jdbcTemplate.query("SELECT * FROM jobs WHERE id = ?", JOB_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<JobRecord> findJobs() {
        return jdbcTemplate.query("SELECT * FROM jobs ORDER BY created_at DESC", JOB_MAPPER);
    }

    public List<JobItemRecord> findItemsByJobId(String jobId) {
        return jdbcTemplate.query(
                "SELECT * FROM job_items WHERE job_id = ? ORDER BY queue_index ASC",
                ITEM_MAPPER,
                jobId
        );
    }

    public void updateJobStatus(
            String jobId,
            JobStatus status,
            String stopReason,
            String lastError,
            String currentCredentialId,
            Integer processedItems,
            boolean setStarted,
            boolean setEnded
    ) {
        String startedAt = setStarted ? SqlTime.nowText() : null;
        String endedAt = setEnded ? SqlTime.nowText() : null;
        jdbcTemplate.update(
                """
                UPDATE jobs
                   SET status = ?,
                       stop_reason = ?,
                       last_error = ?,
                       current_credential_id = ?,
                       processed_items = COALESCE(?, processed_items),
                       started_at = COALESCE(?, started_at),
                       ended_at = COALESCE(?, ended_at)
                 WHERE id = ?
                """,
                status.name(),
                stopReason,
                lastError,
                currentCredentialId,
                processedItems,
                startedAt,
                endedAt,
                jobId
        );
    }

    public void updateItem(
            String itemId,
            JobItemStatus status,
            Integer imageDone,
            String pdfPath,
            String errorReason,
            boolean setStarted,
            boolean setEnded
    ) {
        String startedAt = setStarted ? SqlTime.nowText() : null;
        String endedAt = setEnded ? SqlTime.nowText() : null;
        jdbcTemplate.update(
                """
                UPDATE job_items
                   SET status = ?,
                       image_done = COALESCE(?, image_done),
                       pdf_path = COALESCE(?, pdf_path),
                       error_reason = ?,
                       started_at = COALESCE(?, started_at),
                       ended_at = COALESCE(?, ended_at)
                 WHERE id = ?
                """,
                status.name(),
                imageDone,
                pdfPath,
                errorReason,
                startedAt,
                endedAt,
                itemId
        );
    }

    public Optional<JobRecord> findRunningJob() {
        List<JobRecord> rows = jdbcTemplate.query(
                "SELECT * FROM jobs WHERE status = 'RUNNING' ORDER BY created_at DESC LIMIT 1",
                JOB_MAPPER
        );
        return rows.stream().findFirst();
    }

    public void recoverRunningJobsAfterRestart() {
        String now = SqlTime.nowText();
        jdbcTemplate.update(
                """
                UPDATE jobs
                   SET status = 'FAILED',
                       ended_at = COALESCE(ended_at, ?),
                       stop_reason = COALESCE(stop_reason, 'Application restarted'),
                       last_error = COALESCE(last_error, 'Application restarted while job was running')
                 WHERE status = 'RUNNING'
                """,
                now
        );

        jdbcTemplate.update(
                """
                UPDATE job_items
                   SET status = CASE WHEN status = 'RUNNING' THEN 'FAILED' ELSE status END,
                       ended_at = COALESCE(ended_at, ?),
                       error_reason = CASE
                           WHEN status = 'RUNNING' THEN COALESCE(error_reason, 'Application restarted while item was running')
                           ELSE error_reason
                       END
                 WHERE status = 'RUNNING'
                """,
                now
        );
    }

    private String toText(Object value) {
        return value == null ? null : value.toString();
    }
}
