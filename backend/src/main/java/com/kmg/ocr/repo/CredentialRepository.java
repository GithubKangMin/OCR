package com.kmg.ocr.repo;

import com.kmg.ocr.model.CredentialRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class CredentialRepository {
    private final JdbcTemplate jdbcTemplate;

    public CredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CredentialRecord> MAPPER = new RowMapper<>() {
        @Override
        public CredentialRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CredentialRecord(
                    rs.getString("id"),
                    rs.getString("fingerprint"),
                    rs.getString("file_path"),
                    rs.getString("file_name"),
                    rs.getString("account_label"),
                    rs.getString("project_id"),
                    rs.getString("service_account_email"),
                    rs.getString("private_key_id"),
                    rs.getInt("is_active") == 1,
                    SqlTime.parse(rs.getString("created_at")),
                    SqlTime.parse(rs.getString("updated_at"))
            );
        }
    };

    public List<CredentialRecord> findAll() {
        return jdbcTemplate.query("SELECT * FROM credentials ORDER BY file_name ASC", MAPPER);
    }

    public Optional<CredentialRecord> findById(String id) {
        List<CredentialRecord> rows = jdbcTemplate.query(
                "SELECT * FROM credentials WHERE id = ?",
                MAPPER,
                id
        );
        return rows.stream().findFirst();
    }

    public Optional<CredentialRecord> findByFingerprint(String fingerprint) {
        List<CredentialRecord> rows = jdbcTemplate.query(
                "SELECT * FROM credentials WHERE fingerprint = ?",
                MAPPER,
                fingerprint
        );
        return rows.stream().findFirst();
    }

    public void upsert(CredentialRecord record) {
        String now = SqlTime.nowText();
        int updated = jdbcTemplate.update(
                """
                UPDATE credentials
                   SET file_path = ?, file_name = ?, account_label = ?, project_id = ?, service_account_email = ?,
                       private_key_id = ?, is_active = ?, updated_at = ?
                 WHERE fingerprint = ?
                """,
                record.filePath(),
                record.fileName(),
                record.accountLabel(),
                record.projectId(),
                record.serviceAccountEmail(),
                record.privateKeyId(),
                record.active() ? 1 : 0,
                now,
                record.fingerprint()
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO credentials(id, fingerprint, file_path, file_name, account_label, project_id, service_account_email,
                                            private_key_id, is_active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.id(),
                    record.fingerprint(),
                    record.filePath(),
                    record.fileName(),
                    record.accountLabel(),
                    record.projectId(),
                    record.serviceAccountEmail(),
                    record.privateKeyId(),
                    record.active() ? 1 : 0,
                    now,
                    now
            );
        }
    }

    public void setActiveByKnownFingerprints(List<String> activeFingerprints) {
        jdbcTemplate.update("UPDATE credentials SET is_active = 0, updated_at = ?", SqlTime.nowText());
        if (activeFingerprints.isEmpty()) {
            return;
        }

        String placeholders = String.join(",", activeFingerprints.stream().map(v -> "?").toList());
        Object[] args = new Object[activeFingerprints.size() + 1];
        args[0] = SqlTime.nowText();
        for (int i = 0; i < activeFingerprints.size(); i++) {
            args[i + 1] = activeFingerprints.get(i);
        }
        jdbcTemplate.update(
                "UPDATE credentials SET is_active = 1, updated_at = ? WHERE fingerprint IN (" + placeholders + ")",
                args
        );
    }
}
