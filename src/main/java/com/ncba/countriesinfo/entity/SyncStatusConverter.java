package com.ncba.countriesinfo.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SyncStatusConverter implements AttributeConverter<SyncStatus, String> {

    @Override
    public String convertToDatabaseColumn(SyncStatus attribute) {
        return attribute == null ? null : attribute.getDbValue();
    }

    @Override
    public SyncStatus convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (SyncStatus value : SyncStatus.values()) {
            if (value.getDbValue().equals(dbData)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown sync status: " + dbData);
    }
}
