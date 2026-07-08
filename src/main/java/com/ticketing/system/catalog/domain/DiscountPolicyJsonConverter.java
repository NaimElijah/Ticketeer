package com.ticketing.system.catalog.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Serializes an event's {@link DiscountPolicy} to a single JSON text column and rebuilds it on load.
 * Like {@code PurchasePolicyJsonConverter}, a policy is evaluated, never queried, so it is stored as
 * JSON rather than a column. DiscountPolicy is currently a flat percentage, so the JSON is
 * {@code {"discountPercentage": N}}; keeping a converter (rather than a plain column) leaves room for
 * the policy to grow without a schema change. JPA instantiates the converter itself, so it holds its
 * own {@link ObjectMapper}.
 */
@Converter
public class DiscountPolicyJsonConverter implements AttributeConverter<DiscountPolicy, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(DiscountPolicy policy) {
        DiscountPolicy effective = policy == null ? new DiscountPolicy(0.0) : policy;
        ObjectNode node = MAPPER.createObjectNode();
        node.put("discountPercentage", effective.getDiscountPercentage());
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize discount policy to JSON", e);
        }
    }

    @Override
    public DiscountPolicy convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return new DiscountPolicy(0.0);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return new DiscountPolicy(node.get("discountPercentage").asDouble());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse discount policy JSON", e);
        }
    }
}
