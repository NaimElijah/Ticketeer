package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MinTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.OrPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDtoMapper;

/**
 * Specifies {@link PurchasePolicyDtoMapper}, the one mapping both policy editors (company and event)
 * read and save through (#187): what {@code toPolicy} refuses, how it builds composites, and that
 * {@code toDto} describes every policy shape so a save of what was read changes nothing.
 */
class PurchasePolicyDtoMapperTest {

    private static final int MINIMUM_AGE = 18;
    private static final int MINIMUM_TICKETS = 2;
    private static final int MAXIMUM_TICKETS = 6;

    private static PurchasePolicyDTO dtoOfType(String type) {
        return new PurchasePolicyDTO(type, null, null, null, null);
    }

    private static PurchasePolicyDTO ageDto() {
        return new PurchasePolicyDTO("AGE", MINIMUM_AGE, null, null, null);
    }

    private static PurchasePolicyDTO minTicketsDto() {
        return new PurchasePolicyDTO("MIN_TICKETS", null, MINIMUM_TICKETS, null, null);
    }

    private static PurchasePolicyDTO maxTicketsDto() {
        return new PurchasePolicyDTO("MAX_TICKETS", null, null, MAXIMUM_TICKETS, null);
    }

    private static PurchasePolicyDTO composite(String type, List<PurchasePolicyDTO> children) {
        return new PurchasePolicyDTO(type, null, null, null, children);
    }

    // ---- what toPolicy refuses ----

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void GivenNullOrBlankType_WhenToPolicy_ThenRefusedAsTypeRequired(String type) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PurchasePolicyDtoMapper.toPolicy(dtoOfType(type)));

        assertEquals("Purchase policy type is required", refused.getMessage());
    }

    @Test
    void GivenUnknownTypeCode_WhenToPolicy_ThenRefusedNamingTheCode() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PurchasePolicyDtoMapper.toPolicy(dtoOfType("BOGUS")),
                "an unknown code must be refused, never read as NONE");

        assertEquals("Unknown purchase policy type: BOGUS", refused.getMessage());
    }

    @Test
    void GivenAgeLeafWithoutItsMinimumAge_WhenToPolicy_ThenRefusedNamingTheMissingField() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PurchasePolicyDtoMapper.toPolicy(dtoOfType("AGE")));

        assertEquals("minimumAge is required for AGE policy", refused.getMessage());
    }

    @Test
    void GivenMinTicketsLeafWithoutItsCount_WhenToPolicy_ThenRefusedNamingTheMissingField() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PurchasePolicyDtoMapper.toPolicy(dtoOfType("MIN_TICKETS")));

        assertEquals("minimumTickets is required for MIN_TICKETS policy", refused.getMessage());
    }

    @Test
    void GivenMaxTicketsLeafWithoutItsCount_WhenToPolicy_ThenRefusedNamingTheMissingField() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PurchasePolicyDtoMapper.toPolicy(dtoOfType("MAX_TICKETS")));

        assertEquals("maximumTickets is required for MAX_TICKETS policy", refused.getMessage());
    }

    private static Stream<PurchasePolicyDTO> compositesWithFewerThanTwoChildren() {
        return Stream.of(
                composite("AND", null),
                composite("AND", List.of()),
                composite("AND", List.of(ageDto())),
                composite("OR", null),
                composite("OR", List.of()),
                composite("OR", List.of(maxTicketsDto())));
    }

    @ParameterizedTest
    @MethodSource("compositesWithFewerThanTwoChildren")
    void GivenCompositeWithFewerThanTwoChildren_WhenToPolicy_ThenRefusedNamingTheMinimum(PurchasePolicyDTO dto) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> PurchasePolicyDtoMapper.toPolicy(dto));

        assertEquals(dto.type() + " policy must contain at least 2 children", refused.getMessage());
    }

    // ---- how toPolicy builds ----

    @Test
    void GivenAndWithThreeChildren_WhenToPolicy_ThenTheyNestToTheLeft() {
        PurchasePolicy built = PurchasePolicyDtoMapper.toPolicy(
                composite("AND", List.of(ageDto(), minTicketsDto(), maxTicketsDto())));

        assertEquals(new AndPurchasePolicy(
                new AndPurchasePolicy(new AgePurchasePolicy(MINIMUM_AGE), new MinTicketsPurchasePolicy(MINIMUM_TICKETS)),
                new MaxTicketsPurchasePolicy(MAXIMUM_TICKETS)), built,
                "AND[a, b, c] must become (a AND b) AND c, keeping every child");
    }

    @Test
    void GivenLowerCasePaddedTypeCode_WhenToPolicy_ThenReadAsThatType() {
        PurchasePolicy built = PurchasePolicyDtoMapper.toPolicy(composite(" and ", List.of(ageDto(), maxTicketsDto())));

        assertEquals(new AndPurchasePolicy(new AgePurchasePolicy(MINIMUM_AGE),
                new MaxTicketsPurchasePolicy(MAXIMUM_TICKETS)), built,
                "a type code is read trimmed and case-insensitively");
    }

    @Test
    void GivenNullDto_WhenToPolicy_ThenNoPurchasePolicy() {
        assertInstanceOf(NoPurchasePolicy.class, PurchasePolicyDtoMapper.toPolicy(null));
    }

    @Test
    void GivenNoneTypeCode_WhenToPolicy_ThenNoPurchasePolicy() {
        assertInstanceOf(NoPurchasePolicy.class, PurchasePolicyDtoMapper.toPolicy(dtoOfType("NONE")));
    }

    // ---- toDto describes every shape, so toPolicy rebuilds it unchanged ----

    private static Stream<PurchasePolicy> everyPurchasePolicyShape() {
        return Stream.of(
                new NoPurchasePolicy(),
                new AgePurchasePolicy(MINIMUM_AGE),
                new MinTicketsPurchasePolicy(MINIMUM_TICKETS),
                new MaxTicketsPurchasePolicy(MAXIMUM_TICKETS),
                new AndPurchasePolicy(new AgePurchasePolicy(MINIMUM_AGE), new MaxTicketsPurchasePolicy(MAXIMUM_TICKETS)),
                new OrPurchasePolicy(new MinTicketsPurchasePolicy(MINIMUM_TICKETS),
                        new MaxTicketsPurchasePolicy(MAXIMUM_TICKETS)),
                new AndPurchasePolicy(new AgePurchasePolicy(MINIMUM_AGE),
                        new OrPurchasePolicy(new MinTicketsPurchasePolicy(MINIMUM_TICKETS),
                                new MaxTicketsPurchasePolicy(MAXIMUM_TICKETS))),
                new OrPurchasePolicy(new AndPurchasePolicy(new AgePurchasePolicy(MINIMUM_AGE),
                        new MinTicketsPurchasePolicy(MINIMUM_TICKETS)), new MaxTicketsPurchasePolicy(MAXIMUM_TICKETS)),
                new AndPurchasePolicy(new AndPurchasePolicy(new AgePurchasePolicy(MINIMUM_AGE),
                        new MinTicketsPurchasePolicy(MINIMUM_TICKETS)), new MaxTicketsPurchasePolicy(MAXIMUM_TICKETS)));
    }

    @ParameterizedTest
    @MethodSource("everyPurchasePolicyShape")
    void GivenPolicyOfEachShape_WhenToDtoThenToPolicy_ThenAnEqualPolicy(PurchasePolicy policy) {
        PurchasePolicyDTO dto = PurchasePolicyDtoMapper.toDto(policy);

        assertEquals(policy, PurchasePolicyDtoMapper.toPolicy(dto), "described as " + dto);
    }

    @Test
    void GivenNullPolicy_WhenToDto_ThenTypeNone() {
        PurchasePolicyDTO dto = PurchasePolicyDtoMapper.toDto(null);

        assertEquals(PurchasePolicyDtoMapper.PolicyType.NONE.name(), dto.type());
        assertTrue(dto.children() == null || dto.children().isEmpty(), "a NONE policy has no children; got " + dto);
    }
}
