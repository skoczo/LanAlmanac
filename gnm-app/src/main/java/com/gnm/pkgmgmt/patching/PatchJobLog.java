package com.gnm.pkgmgmt.patching;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "patch_job_log")
@IdClass(PatchJobLog.PatchJobLogId.class)
public class PatchJobLog extends PanacheEntityBase {

    public static class PatchJobLogId implements Serializable {
        public UUID jobId;
        public long seq;

        public PatchJobLogId() {}

        public PatchJobLogId(UUID jobId, long seq) {
            this.jobId = jobId;
            this.seq = seq;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PatchJobLogId that)) return false;
            return seq == that.seq && Objects.equals(jobId, that.jobId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, seq);
        }
    }

    @Id
    @Column(name = "job_id", nullable = false)
    public UUID jobId;

    @Id
    @Column(name = "seq", nullable = false)
    public long seq;

    @Column(name = "stream", nullable = false, length = 16)
    public String stream;

    @Column(name = "ts", nullable = false)
    public Instant ts = Instant.now();

    @Column(name = "line", nullable = false)
    public String line;
}

