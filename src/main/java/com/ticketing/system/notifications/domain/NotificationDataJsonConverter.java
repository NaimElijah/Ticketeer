package com.ticketing.system.notifications.domain;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores a {@link Notification}'s {@code Map<String,Object> data} payload as a single
 * JSON text column instead of an {@code @ElementCollection} side table. Jackson turns
 * the map into JSON text on the way to the database and parses it back on the way out.
 *
 * <p>Round-trip fidelity: String, boolean, whole numbers, decimals, lists and nested maps
 * come back equal. JSON carries no Java type tags, so an exotic {@code Long} comes back as
 * {@code Integer} and a {@code BigDecimal} as {@code Double} (same value, different flavor)
 * — acceptable for this render-only payload, and not a type our notifications use.
 *
 * <p>Lives next to {@link Notification} (referenced by its {@code @Convert}) rather than in
 * the infrastructure layer, so a core class never imports downward. JPA instantiates the
 * converter itself (it is not a Spring bean), so it holds its own {@link ObjectMapper}.
 */
@Converter
public class NotificationDataJsonConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(data == null ? new HashMap<>() : data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize notification data to JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse notification data JSON", e);
        }
    }
}
