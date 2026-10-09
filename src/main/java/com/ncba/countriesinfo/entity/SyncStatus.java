package com.ncba.countriesinfo.entity;

/**
 * Outcome recorded for one sync log entry.
 */
public enum SyncStatus {
    SUCCESS("success"),
    FAILED("failed");

    private final String dbValue;

    SyncStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }
}
