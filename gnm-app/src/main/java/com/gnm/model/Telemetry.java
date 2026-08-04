package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "telemetry")
public class Telemetry extends PanacheEntityBase {

    @EmbeddedId
    public TelemetryId id;

    @Column(name = "value", nullable = false)
    public Double value;

    @Column(name = "labels")
    public String labels;

    @Embeddable
    public static class TelemetryId implements Serializable {
        
        @Column(name = "time", nullable = false)
        public Instant time;

        @Column(name = "physical_device_id", nullable = false)
        public UUID physicalDeviceId;

        @Column(name = "metric_name", nullable = false)
        public String metricName;

        public TelemetryId() {}

        public TelemetryId(Instant time, UUID physicalDeviceId, String metricName) {
            this.time = time;
            this.physicalDeviceId = physicalDeviceId;
            this.metricName = metricName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TelemetryId that = (TelemetryId) o;
            return Objects.equals(time, that.time) &&
                   Objects.equals(physicalDeviceId, that.physicalDeviceId) &&
                   Objects.equals(metricName, that.metricName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(time, physicalDeviceId, metricName);
        }
    }
}
