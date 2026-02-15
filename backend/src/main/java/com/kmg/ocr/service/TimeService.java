package com.kmg.ocr.service;

import com.kmg.ocr.config.OcrProperties;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class TimeService {
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private final ZoneId zoneId;

    public TimeService(OcrProperties properties) {
        this.zoneId = ZoneId.of(properties.getCredentials().getTimezone());
    }

    public String currentPeriod() {
        return PERIOD_FORMAT.format(ZonedDateTime.now(zoneId));
    }

    public ZonedDateTime now() {
        return ZonedDateTime.now(zoneId);
    }

    public ZonedDateTime nextResetAt() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime firstOfMonth = now.withDayOfMonth(1).toLocalDate().atStartOfDay(zoneId);
        if (now.isBefore(firstOfMonth)) {
            return firstOfMonth;
        }
        return firstOfMonth.plusMonths(1);
    }

    public ZoneId zoneId() {
        return zoneId;
    }
}
