package com.ticketing.system.architecture.fixture.catalog.domain;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.persistence.AttributeConverter;

/**
 * Allowed case, as in {@code NotificationDataJsonConverter}: the anonymous {@code TypeReference}
 * subclass compiles to a nested class of its own, which uses Jackson on the converter's behalf.
 * Deliberately not annotated {@code @Converter}, so no JPA scan registers it.
 */
public class ConverterWithTypeReference implements AttributeConverter<Map<String, Object>, String> {

    /** The nested class the rule must treat as part of this converter. */
    public static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        return String.valueOf(attribute);
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        return Map.of();
    }
}
