package com.kmg.ocr.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class OcrCheckpointRepository {
    private final JdbcTemplate jdbcTemplate;

    public OcrCheckpointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CheckpointRow> ROW_MAPPER = new RowMapper<>() {
        @Override
        public CheckpointRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CheckpointRow(
                    rs.getString("job_item_id"),
                    rs.getInt("page_index"),
                    rs.getString("image_path"),
                    rs.getString("full_text"),
                    rs.getString("words_json")
            );
        }
    };

    public void upsert(String jobItemId, int pageIndex, String imagePath, String fullText, String wordsJson) {
        String now = SqlTime.nowText();
        int updated = jdbcTemplate.update(
                """
                UPDATE ocr_page_checkpoints
                   SET image_path = ?, full_text = ?, words_json = ?, updated_at = ?
                 WHERE job_item_id = ? AND page_index = ?
                """,
                imagePath,
                fullText,
                wordsJson,
                now,
                jobItemId,
                pageIndex
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO ocr_page_checkpoints(job_item_id, page_index, image_path, full_text, words_json, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    jobItemId,
                    pageIndex,
                    imagePath,
                    fullText,
                    wordsJson,
                    now
            );
        }
    }

    public List<CheckpointRow> findByItemId(String jobItemId) {
        return jdbcTemplate.query(
                "SELECT * FROM ocr_page_checkpoints WHERE job_item_id = ? ORDER BY page_index ASC",
                ROW_MAPPER,
                jobItemId
        );
    }

    public void deleteByItemId(String jobItemId) {
        jdbcTemplate.update("DELETE FROM ocr_page_checkpoints WHERE job_item_id = ?", jobItemId);
    }

    public record CheckpointRow(
            String jobItemId,
            int pageIndex,
            String imagePath,
            String fullText,
            String wordsJson
    ) {
    }
}
