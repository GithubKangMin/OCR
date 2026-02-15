package com.kmg.ocr.repo;

import com.kmg.ocr.model.UsageRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class UsageRepository {
    private final JdbcTemplate jdbcTemplate;

    public UsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<UsageRecord> MAPPER = new RowMapper<>() {
        @Override
        public UsageRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new UsageRecord(
                    rs.getString("credential_id"),
                    rs.getString("period_pt"),
                    rs.getInt("cap_units"),
                    rs.getInt("used_units"),
                    rs.getInt("adjusted_units"),
                    SqlTime.parse(rs.getString("updated_at"))
            );
        }
    };

    public List<UsageRecord> findByPeriod(String period) {
        return jdbcTemplate.query(
                "SELECT * FROM usage_monthly WHERE period_pt = ?",
                MAPPER,
                period
        );
    }

    public Optional<UsageRecord> findByCredentialAndPeriod(String credentialId, String period) {
        List<UsageRecord> rows = jdbcTemplate.query(
                "SELECT * FROM usage_monthly WHERE credential_id = ? AND period_pt = ?",
                MAPPER,
                credentialId,
                period
        );
        return rows.stream().findFirst();
    }

    @Transactional
    public UsageRecord ensureRow(String credentialId, String period, int capUnits) {
        Optional<UsageRecord> existing = findByCredentialAndPeriod(credentialId, period);
        if (existing.isPresent()) {
            return existing.get();
        }
        String now = SqlTime.nowText();
        jdbcTemplate.update(
                """
                INSERT INTO usage_monthly(credential_id, period_pt, cap_units, used_units, adjusted_units, updated_at)
                VALUES (?, ?, ?, 0, 0, ?)
                """,
                credentialId,
                period,
                capUnits,
                now
        );
        return findByCredentialAndPeriod(credentialId, period).orElseThrow();
    }

    @Transactional
    public UsageRecord incrementUsed(String credentialId, String period, int capUnits, int delta) {
        ensureRow(credentialId, period, capUnits);
        jdbcTemplate.update(
                "UPDATE usage_monthly SET used_units = used_units + ?, updated_at = ? WHERE credential_id = ? AND period_pt = ?",
                delta,
                SqlTime.nowText(),
                credentialId,
                period
        );
        return findByCredentialAndPeriod(credentialId, period).orElseThrow();
    }

    @Transactional
    public UsageRecord setUsed(String credentialId, String period, int capUnits, int newUsed) {
        ensureRow(credentialId, period, capUnits);
        jdbcTemplate.update(
                "UPDATE usage_monthly SET used_units = ?, updated_at = ? WHERE credential_id = ? AND period_pt = ?",
                Math.max(0, newUsed),
                SqlTime.nowText(),
                credentialId,
                period
        );
        return findByCredentialAndPeriod(credentialId, period).orElseThrow();
    }

    @Transactional
    public void addAudit(String credentialId, String period, int oldUsed, int newUsed, String reason) {
        jdbcTemplate.update(
                """
                INSERT INTO usage_audit(credential_id, period_pt, old_used_units, new_used_units, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                credentialId,
                period,
                oldUsed,
                newUsed,
                reason,
                SqlTime.nowText()
        );
    }
}
