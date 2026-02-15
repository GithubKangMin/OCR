package com.kmg.ocr.repo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class SqlTime {
    private SqlTime() {
    }

    public static String nowText() {
        return OffsetDateTime.now(ZoneOffset.UTC).toString();
    }

    public static OffsetDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }
}
