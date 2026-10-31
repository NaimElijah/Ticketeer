package com.ticketing.system.shared.dto;

import java.util.List;
import java.util.Locale;

import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MinTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.OrPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;

/**
 * Maps the shared purchase-policy domain to and from {@link PurchasePolicyDTO}, the form the policy
 * editors read and save back. Organization's company policy and catalog's event policy both use it,
 * so the two accept the same input and write the same codes.
 *
 * <p>It lives beside the DTO rather than in {@code shared.domain.policy}, which stays free of DTO
 * types.
 */
public final class PurchasePolicyDtoMapper {

    /** The codes {@link PurchasePolicyDTO#type()} carries, one per policy class. */
    public enum PolicyType {
        NONE, AGE, MIN_TICKETS, MAX_TICKETS, AND, OR
    }

    private static final int MIN_COMPOSITE_CHILDREN = 2;

    private PurchasePolicyDtoMapper() {
    }

    /**
     * Builds the policy a DTO describes. A composite with more than two children nests to the left:
     * {@code AND[a, b, c]} becomes {@code (a AND b) AND c}.
     *
     * @param dto the policy as the editor sent it; {@code null} means no policy
     * @return the policy, {@link NoPurchasePolicy} for {@code null} or {@code NONE}
     * @throws IllegalArgumentException if the type is blank or unknown, a leaf lacks its value, or a
     *                                  composite has fewer than two children
     */
    public static PurchasePolicy toPolicy(PurchasePolicyDTO dto) {
        if (dto == null) {
            return new NoPurchasePolicy();
        }
        return switch (typeOf(dto)) {
            case NONE -> new NoPurchasePolicy();
            case AGE -> new AgePurchasePolicy(required(dto.minimumAge(), "minimumAge", PolicyType.AGE));
            case MIN_TICKETS -> new MinTicketsPurchasePolicy(
                    required(dto.minimumTickets(), "minimumTickets", PolicyType.MIN_TICKETS));
            case MAX_TICKETS -> new MaxTicketsPurchasePolicy(
                    required(dto.maximumTickets(), "maximumTickets", PolicyType.MAX_TICKETS));
            case AND -> foldChildren(dto, PolicyType.AND);
            case OR -> foldChildren(dto, PolicyType.OR);
        };
    }

    /**
     * Describes a policy as a DTO the editor can show and save back unchanged.
     *
     * @param policy the stored policy; {@code null} means no policy
     * @return the DTO, of type {@code NONE} for {@code null} or {@link NoPurchasePolicy}
     * @throws IllegalStateException if the policy, or one nested in it, is of a class this mapper does
     *                               not know: reading it as {@code NONE} would let a save erase it
     */
    public static PurchasePolicyDTO toDto(PurchasePolicy policy) {
        if (policy == null || policy instanceof NoPurchasePolicy) {
            return leaf(PolicyType.NONE, null, null, null);
        }
        if (policy instanceof AgePurchasePolicy age) {
            return leaf(PolicyType.AGE, age.getMinimumAge(), null, null);
        }
        if (policy instanceof MinTicketsPurchasePolicy min) {
            return leaf(PolicyType.MIN_TICKETS, null, min.getMinimumTickets(), null);
        }
        if (policy instanceof MaxTicketsPurchasePolicy max) {
            return leaf(PolicyType.MAX_TICKETS, null, null, max.getMaximumTickets());
        }
        if (policy instanceof AndPurchasePolicy and) {
            return composite(PolicyType.AND, and.getLeftPolicy(), and.getRightPolicy());
        }
        if (policy instanceof OrPurchasePolicy or) {
            return composite(PolicyType.OR, or.getLeftPolicy(), or.getRightPolicy());
        }
        throw new IllegalStateException("No DTO mapping for purchase policy type " + policy.getClass().getName());
    }

    private static PolicyType typeOf(PurchasePolicyDTO dto) {
        if (dto.type() == null || dto.type().isBlank()) {
            throw new IllegalArgumentException("Purchase policy type is required");
        }
        try {
            return PolicyType.valueOf(dto.type().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown purchase policy type: " + dto.type(), e);
        }
    }

    private static int required(Integer value, String field, PolicyType type) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required for " + type + " policy");
        }
        return value;
    }

    private static PurchasePolicy foldChildren(PurchasePolicyDTO dto, PolicyType type) {
        List<PurchasePolicyDTO> children = dto.children();
        if (children == null || children.size() < MIN_COMPOSITE_CHILDREN) {
            throw new IllegalArgumentException(
                    type + " policy must contain at least " + MIN_COMPOSITE_CHILDREN + " children");
        }
        PurchasePolicy result = toPolicy(children.get(0));
        for (PurchasePolicyDTO child : children.subList(1, children.size())) {
            PurchasePolicy next = toPolicy(child);
            result = type == PolicyType.AND ? new AndPurchasePolicy(result, next) : new OrPurchasePolicy(result, next);
        }
        return result;
    }

    private static PurchasePolicyDTO leaf(PolicyType type, Integer minimumAge, Integer minimumTickets,
            Integer maximumTickets) {
        return new PurchasePolicyDTO(type.name(), minimumAge, minimumTickets, maximumTickets, null);
    }

    private static PurchasePolicyDTO composite(PolicyType type, PurchasePolicy left, PurchasePolicy right) {
        return new PurchasePolicyDTO(type.name(), null, null, null, List.of(toDto(left), toDto(right)));
    }
}
