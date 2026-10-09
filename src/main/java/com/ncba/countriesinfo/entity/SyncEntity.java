package com.ncba.countriesinfo.entity;

/**
 * Kind of data a sync log entry refers to, stored as the literal enum name.
 */
public enum SyncEntity {
    COUNTRY("country"),
    LANGUAGE("language");

    private final String dbValue;

    SyncEntity(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }
}
