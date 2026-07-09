package com.ticketing.system.shared.domain.policy;

// NOTE: no import of organization.domain.ProductionCompany — the class is referenced only by a
// fully-qualified {@link} in the Javadoc below, so an import would be unused and would (wrongly)
// make this shared-kernel type depend on the organization context. Kept out to keep policy a clean
// shared citizen.
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Serializes a {@link PurchasePolicy} tree to a single JSON text column and rebuilds it on load.
 *
 * <p>Purchase policies are a recursive Composite (And/Or over Age/Max/Min/None leaves) that are
 * <em>evaluated</em>, never queried by sub-policy — so the whole tree is stored as JSON rather than
 * spread across tables (V3 plan §6). Serialization walks the tree explicitly via the policies'
 * existing getters and dispatches on a small {@code "type"} tag, so there is no reflection or
 * polymorphic-deserialization magic; reconstruction calls the same public constructors recursively.
 *
 * <p>Shared by both policy owners — {@link com.ticketing.system.organization.domain.ProductionCompany}
 * (B5) and Event (B9). JPA instantiates the converter itself (it is not a Spring bean), so it holds
 * its own {@link ObjectMapper}. Lives next to the policies it serializes — a domain-to-domain
 * reference, not a downward one.
 */
@Converter
public class PurchasePolicyJsonConverter implements AttributeConverter<PurchasePolicy, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(PurchasePolicy policy) {
        try {
            return MAPPER.writeValueAsString(toNode(policy == null ? new NoPurchasePolicy() : policy));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize purchase policy to JSON", e);
        }
    }

    @Override
    public PurchasePolicy convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return new NoPurchasePolicy();
        }
        try {
            return fromNode(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse purchase policy JSON", e);
        }
    }

    private JsonNode toNode(PurchasePolicy policy) {
        ObjectNode node = MAPPER.createObjectNode();
        if (policy instanceof NoPurchasePolicy) {
            node.put("type", "none");
        } else if (policy instanceof AgePurchasePolicy age) {
            node.put("type", "age");
            node.put("minimumAge", age.getMinimumAge());
        } else if (policy instanceof MaxTicketsPurchasePolicy max) {
            node.put("type", "maxTickets");
            node.put("maximumTickets", max.getMaximumTickets());
        } else if (policy instanceof MinTicketsPurchasePolicy min) {
            node.put("type", "minTickets");
            node.put("minimumTickets", min.getMinimumTickets());
        } else if (policy instanceof AndPurchasePolicy and) {
            node.put("type", "and");
            node.set("left", toNode(and.getLeftPolicy()));
            node.set("right", toNode(and.getRightPolicy()));
        } else if (policy instanceof OrPurchasePolicy or) {
            node.put("type", "or");
            node.set("left", toNode(or.getLeftPolicy()));
            node.set("right", toNode(or.getRightPolicy()));
        } else {
            throw new IllegalArgumentException("Unknown purchase policy type: " + policy.getClass().getName());
        }
        return node;
    }

    private PurchasePolicy fromNode(JsonNode node) {
        String type = node.get("type").asText();
        return switch (type) {
            case "none" -> new NoPurchasePolicy();
            case "age" -> new AgePurchasePolicy(node.get("minimumAge").asInt());
            case "maxTickets" -> new MaxTicketsPurchasePolicy(node.get("maximumTickets").asInt());
            case "minTickets" -> new MinTicketsPurchasePolicy(node.get("minimumTickets").asInt());
            case "and" -> new AndPurchasePolicy(fromNode(node.get("left")), fromNode(node.get("right")));
            case "or" -> new OrPurchasePolicy(fromNode(node.get("left")), fromNode(node.get("right")));
            default -> throw new IllegalArgumentException("Unknown purchase policy type: " + type);
        };
    }
}
