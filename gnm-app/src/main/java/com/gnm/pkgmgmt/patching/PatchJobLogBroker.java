package com.gnm.pkgmgmt.patching;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@ApplicationScoped
public class PatchJobLogBroker {

    public static final int MAX_LINES_PER_JOB = 50_000;
    public static final int MAX_BYTES_PER_JOB = 10 * 1024 * 1024;

    private static final class JobLogState {
        final List<PatchJobLog> buffer = new ArrayList<>();
        final List<Consumer<PatchJobLog>> subscribers = new CopyOnWriteArrayList<>();
        long nextSeq = 1;
        long totalBytes = 0;
        boolean truncated = false;
        boolean completed = false;
    }

    private final ConcurrentHashMap<UUID, JobLogState> jobs = new ConcurrentHashMap<>();

    public synchronized PatchJobLog append(UUID jobId, String stream, String line) {
        JobLogState state = jobs.computeIfAbsent(jobId, id -> new JobLogState());
        if (state.truncated) {
            return null;
        }
        String sanitized = sanitize(line);
        if (state.buffer.size() >= MAX_LINES_PER_JOB
                || state.totalBytes + sanitized.length() > MAX_BYTES_PER_JOB) {
            state.truncated = true;
            PatchJobLog truncEntry = new PatchJobLog();
            truncEntry.jobId = jobId;
            truncEntry.seq = state.nextSeq++;
            truncEntry.stream = "SYSTEM";
            truncEntry.ts = Instant.now();
            truncEntry.line = "[Log truncated: maximum log limit reached]";
            state.buffer.add(truncEntry);
            for (Consumer<PatchJobLog> sub : state.subscribers) {
                sub.accept(truncEntry);
            }
            return truncEntry;
        }

        PatchJobLog entry = new PatchJobLog();
        entry.jobId = jobId;
        entry.seq = state.nextSeq++;
        entry.stream = stream != null ? stream : "STDOUT";
        entry.ts = Instant.now();
        entry.line = sanitized;

        state.totalBytes += sanitized.length();
        state.buffer.add(entry);
        for (Consumer<PatchJobLog> sub : state.subscribers) {
            sub.accept(entry);
        }
        return entry;
    }

    public synchronized Consumer<PatchJobLog> subscribeWithReplay(
            UUID jobId,
            long afterSeq,
            List<PatchJobLog> dbHistory,
            Consumer<PatchJobLog> listener) {
        JobLogState state = jobs.computeIfAbsent(jobId, id -> new JobLogState());

        Map<Long, PatchJobLog> merged = new TreeMap<>();
        if (dbHistory != null) {
            for (PatchJobLog l : dbHistory) {
                if (l != null && l.seq > afterSeq) {
                    merged.put(l.seq, l);
                }
            }
        }
        for (PatchJobLog l : state.buffer) {
            if (l.seq > afterSeq) {
                merged.put(l.seq, l);
            }
        }

        merged.values().stream()
                .sorted(Comparator.comparingLong(l -> l.seq))
                .forEach(listener);

        if (!state.completed) {
            state.subscribers.add(listener);
        }
        return listener;
    }

    public synchronized void unsubscribe(UUID jobId, Consumer<PatchJobLog> listener) {
        JobLogState state = jobs.get(jobId);
        if (state != null && listener != null) {
            state.subscribers.remove(listener);
        }
    }

    public synchronized void completeJob(UUID jobId) {
        JobLogState state = jobs.get(jobId);
        if (state != null) {
            state.completed = true;
            state.subscribers.clear();
        }
    }

    public synchronized boolean isCompleted(UUID jobId) {
        JobLogState state = jobs.get(jobId);
        return state != null && state.completed;
    }

    private static String sanitize(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.replace("\r", "");
        if (trimmed.length() > 4096) {
            return trimmed.substring(0, 4096);
        }
        return trimmed;
    }
}

