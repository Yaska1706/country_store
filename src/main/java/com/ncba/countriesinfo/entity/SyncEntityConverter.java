package com.ncba.countriesinfo.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SyncEntityConverter implements AttributeConverter<SyncEntity, String> {

    @Override
    public String convertToDatabaseColumn(SyncEntity attribute) {
        return attribute == null ? null : attribute.getDbValue();
    }

    @Override
    public SyncEntity convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        for (SyncEntity value : SyncEntity.values()) {
            if (value.getDbValue().equals(dbData)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown sync entity type: " + dbData);
    }
}
