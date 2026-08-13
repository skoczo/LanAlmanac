package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "fingerprint_vector")
public class FingerprintVector extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_device_id", nullable = false)
    @JsonIgnore
    public PhysicalDevice physicalDevice;

    @Column(name = "version")
    public Integer version = 1;

    @Column(name = "dhcp_option55")
    public String dhcpOption55;

    @Column(name = "dhcp_option60")
    public String dhcpOption60;

    @Column(name = "tcp_fingerprint")
    public String tcpFingerprint;

    @Column(name = "mdns_services")
    @JdbcTypeCode(SqlTypes.ARRAY)
    public List<String> mdnsServices;

    @Column(name = "ssdp_usn")
    public String ssdpUsn;

    @Column(name = "http_server_header")
    public String httpServerHeader;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "fingerprint_vector_ssh_keys", joinColumns = @JoinColumn(name = "fingerprint_vector_id"))
    @Column(name = "ssh_host_key")
    public List<String> sshHostKeys = new ArrayList<>();

    @Column(name = "tls_ja4")
    public String tlsJa4;

    @Column(name = "tls_cert_subject")
    public String tlsCertSubject;

    @Column(name = "open_ports")
    @JdbcTypeCode(SqlTypes.ARRAY)
    public List<Integer> openPorts;

    @Column(name = "mac_oui")
    public String macOui;

    @Column(name = "captured_at", nullable = false)
    public Instant capturedAt;

    @Column(name = "hostname")
    public String hostname;
}
