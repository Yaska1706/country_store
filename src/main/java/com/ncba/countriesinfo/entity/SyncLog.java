package com.ncba.countriesinfo.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "sync_logs", indexes = {
        @Index(name = "idx_sync_logs_country_synced_at", columnList = "country_iso_code, synced_at")
})
public class SyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false)
    @Convert(converter = SyncEntityConverter.class)
    private SyncEntity entityType;

    @Column(name = "country_iso_code", nullable = false, length = 2, columnDefinition = "char(2)")
    private String countryIsoCode;

    @Column(name = "status", nullable = false)
    @Convert(converter = SyncStatusConverter.class)
    private SyncStatus status;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @CreationTimestamp
    @Column(name = "synced_at", nullable = false, updatable = false)
    private LocalDateTime syncedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public SyncEntity getEntityType() { return entityType; }
    public void setEntityType(SyncEntity entityType) { this.entityType = entityType; }
    public String getCountryIsoCode() { return countryIsoCode; }
    public void setCountryIsoCode(String countryIsoCode) { this.countryIsoCode = countryIsoCode; }
    public SyncStatus getStatus() { return status; }
    public void setStatus(SyncStatus status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getSyncedAt() { return syncedAt; }
}
