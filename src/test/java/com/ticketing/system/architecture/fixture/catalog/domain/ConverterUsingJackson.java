package com.ticketing.system.architecture.fixture.catalog.domain;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;

/**
 * Allowed case: a JPA attribute converter serialising a column with Jackson. Deliberately not
 * annotated {@code @Converter}, so no JPA scan registers it.
 */
public class ConverterUsingJackson implements AttributeConverter<String, String> {

    private ObjectMapper mapper;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData;
    }
}
