package com.gnm.pkgmgmt.schedule;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Standard 5-field POSIX cron calculator (`minute hour day-of-month month day-of-week`)
 * with full timezone support.
 */
@ApplicationScoped
public class CronScheduleCalculator {

    public ZoneId parseTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new BadRequestException("Timezone is required");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception e) {
            throw new BadRequestException("Invalid timezone: " + timezone);
        }
    }

    public Instant nextExecution(String cronExpression, String timezone, Instant after) {
        List<Instant> next = previewNextExecutions(cronExpression, timezone, after, 1);
        return next.getFirst();
    }

    public List<Instant> previewNextExecutions(
            String cronExpression, String timezone, Instant after, int count) {
        ZoneId zoneId = parseTimezone(timezone);
        ParsedCron cron = parseCron(cronExpression);
        List<Instant> results = new ArrayList<>(Math.max(1, count));

        ZonedDateTime candidate =
                after.atZone(zoneId).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        int maxIterations = 366 * 24 * 60; // 1 year in minutes

        for (int i = 0; i < maxIterations && results.size() < count; i++) {
            if (cron.matches(candidate)) {
                results.add(candidate.toInstant());
            }
            candidate = candidate.plusMinutes(1);
        }

        if (results.isEmpty()) {
            throw new BadRequestException(
                    "Cron expression produces no execution within 366 days: " + cronExpression);
        }
        return results;
    }

    private ParsedCron parseCron(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new BadRequestException("Cron expression cannot be empty");
        }
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) {
            throw new BadRequestException("Cron expression must have 5 fields (min hour dom month dow)");
        }
        BitSet minutes = parseField(parts[0], 0, 59, false);
        BitSet hours = parseField(parts[1], 0, 23, false);
        BitSet daysOfMonth = parseField(parts[2], 1, 31, false);
        BitSet months = parseField(parts[3], 1, 12, false);
        BitSet daysOfWeek = parseField(parts[4], 0, 7, true);
        if (daysOfWeek.get(7)) {
            daysOfWeek.set(0); // 7 and 0 both mean Sunday
        }
        return new ParsedCron(minutes, hours, daysOfMonth, months, daysOfWeek);
    }

    private BitSet parseField(String field, int min, int max, boolean allowSevenForSunday) {
        BitSet set = new BitSet(max + 1);
        String[] segments = field.split(",");
        for (String seg : segments) {
            seg = seg.trim();
            if (seg.isEmpty()) {
                throw new BadRequestException("Invalid empty cron field segment");
            }
            int step = 1;
            String rangePart = seg;
            int slashIdx = seg.indexOf('/');
            if (slashIdx >= 0) {
                rangePart = seg.substring(0, slashIdx);
                try {
                    step = Integer.parseInt(seg.substring(slashIdx + 1));
                } catch (NumberFormatException e) {
                    throw new BadRequestException("Invalid cron step in: " + seg);
                }
                if (step <= 0) {
                    throw new BadRequestException("Cron step must be positive: " + seg);
                }
            }

            int start;
            int end;
            if ("*".equals(rangePart)) {
                start = min;
                end = allowSevenForSunday ? 6 : max;
            } else if (rangePart.contains("-")) {
                String[] bounds = rangePart.split("-", 2);
                start = parseIntBound(bounds[0], min, max);
                end = parseIntBound(bounds[1], min, max);
                if (start > end) {
                    throw new BadRequestException("Invalid cron range: " + seg);
                }
            } else {
                start = parseIntBound(rangePart, min, max);
                end = slashIdx >= 0 ? (allowSevenForSunday ? 6 : max) : start;
            }

            for (int v = start; v <= end; v += step) {
                set.set(v);
            }
        }
        return set;
    }

    private int parseIntBound(String s, int min, int max) {
        try {
            int val = Integer.parseInt(s);
            if (val < min || val > max) {
                throw new BadRequestException(
                        "Cron value " + val + " out of bounds [" + min + ".." + max + "]");
            }
            return val;
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid cron number: " + s);
        }
    }

    private record ParsedCron(
            BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months, BitSet daysOfWeek) {

        boolean matches(ZonedDateTime dt) {
            int minute = dt.getMinute();
            int hour = dt.getHour();
            int dom = dt.getDayOfMonth();
            int month = dt.getMonthValue();
            int dow = dt.getDayOfWeek().getValue() % 7; // Sunday = 0, Monday = 1..Saturday = 6
            return minutes.get(minute)
                    && hours.get(hour)
                    && daysOfMonth.get(dom)
                    && months.get(month)
                    && daysOfWeek.get(dow);
        }
    }
}

