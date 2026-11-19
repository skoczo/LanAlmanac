package com.gnm.pkgmgmt.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.BadRequestException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CronScheduleCalculatorTest {

    @Test
    void computesNextRunAndPreviewRunsAcrossTimezones() {
        CronScheduleCalculator calc = new CronScheduleCalculator();
        Instant base = Instant.parse("2026-03-15T10:00:00Z");

        Instant nextUtc = calc.nextExecution("0 3 * * *", "UTC", base);
        assertEquals(Instant.parse("2026-03-16T03:00:00Z"), nextUtc);

        List<Instant> previewWarsaw = calc.previewNextExecutions("30 2 * * 0", "Europe/Warsaw", base, 3);
        assertEquals(3, previewWarsaw.size());
        for (int i = 0; i < previewWarsaw.size(); i++) {
            ZonedDateTime zdt = previewWarsaw.get(i).atZone(ZoneId.of("Europe/Warsaw"));
            assertEquals(2, zdt.getHour());
            assertEquals(30, zdt.getMinute());
            if (i > 0) {
                assertTrue(previewWarsaw.get(i).isAfter(previewWarsaw.get(i - 1)));
            }
        }
    }

    @Test
    void supportsStepAndListExpressions() {
        CronScheduleCalculator calc = new CronScheduleCalculator();
        Instant base = Instant.parse("2026-03-15T10:10:00Z");

        Instant next = calc.nextExecution("*/15 * * * *", "UTC", base);
        assertNotNull(next);
        assertEquals(Instant.parse("2026-03-15T10:15:00Z"), next);
    }

    @Test
    void rejectsInvalidCronOrTimezone() {
        CronScheduleCalculator calc = new CronScheduleCalculator();
        Instant base = Instant.parse("2026-03-15T10:00:00Z");

        assertThrows(BadRequestException.class, () -> calc.nextExecution("invalid cron", "UTC", base));
        assertThrows(BadRequestException.class, () -> calc.nextExecution("99 99 * * *", "UTC", base));
        assertThrows(BadRequestException.class, () -> calc.nextExecution("0 3 * * *", "Mars/Phobos", base));
    }
}

