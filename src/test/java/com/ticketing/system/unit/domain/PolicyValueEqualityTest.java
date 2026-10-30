package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.DiscountPolicyJsonConverter;
import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MinTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.OrPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicyJsonConverter;

/**
 * Hibernate decides whether a converted column changed by comparing the entity's value with a copy it
 * made through the converter, using {@code equals}. Every purchase and discount policy must therefore
 * equal its own JSON round trip, or the owning event or company row is rewritten on every flush (#186).
 */
class PolicyValueEqualityTest {

    private final PurchasePolicyJsonConverter purchaseConverter = new PurchasePolicyJsonConverter();
    private final DiscountPolicyJsonConverter discountConverter = new DiscountPolicyJsonConverter();

    static Stream<PurchasePolicy> purchasePolicies() {
        return Stream.of(
                new NoPurchasePolicy(),
                new AgePurchasePolicy(18),
                new MaxTicketsPurchasePolicy(4),
                new MinTicketsPurchasePolicy(2),
                new AndPurchasePolicy(new AgePurchasePolicy(18), new MaxTicketsPurchasePolicy(4)),
                new OrPurchasePolicy(new MinTicketsPurchasePolicy(2),
                        new AndPurchasePolicy(new AgePurchasePolicy(21), new NoPurchasePolicy())));
    }

    @ParameterizedTest
    @MethodSource("purchasePolicies")
    void GivenAPurchasePolicy_WhenRoundTrippedThroughItsConverter_ThenTheCopyEqualsIt(PurchasePolicy policy) {
        PurchasePolicy copy = purchaseConverter.convertToEntityAttribute(purchaseConverter.convertToDatabaseColumn(policy));

        assertEquals(policy, copy);
        assertEquals(policy.hashCode(), copy.hashCode());
    }

    @Test
    void GivenADiscountPolicy_WhenRoundTrippedThroughItsConverter_ThenTheCopyEqualsIt() {
        DiscountPolicy policy = new DiscountPolicy(15.0);

        DiscountPolicy copy = discountConverter.convertToEntityAttribute(discountConverter.convertToDatabaseColumn(policy));

        assertEquals(policy, copy);
        assertEquals(policy.hashCode(), copy.hashCode());
    }

    static Stream<Arguments> differentPolicies() {
        AgePurchasePolicy adults = new AgePurchasePolicy(18);
        MaxTicketsPurchasePolicy upToFour = new MaxTicketsPurchasePolicy(4);
        return Stream.of(
                Arguments.of(adults, new AgePurchasePolicy(21)),
                Arguments.of(upToFour, new MaxTicketsPurchasePolicy(5)),
                Arguments.of(new MinTicketsPurchasePolicy(2), new MinTicketsPurchasePolicy(3)),
                Arguments.of(new MinTicketsPurchasePolicy(2), new MaxTicketsPurchasePolicy(2)),
                Arguments.of(new NoPurchasePolicy(), adults),
                Arguments.of(new AndPurchasePolicy(adults, upToFour), new AndPurchasePolicy(upToFour, adults)),
                Arguments.of(new AndPurchasePolicy(adults, upToFour), new OrPurchasePolicy(adults, upToFour)),
                Arguments.of(new OrPurchasePolicy(adults, new NoPurchasePolicy()),
                        new OrPurchasePolicy(adults, new AgePurchasePolicy(21))),
                Arguments.of(new DiscountPolicy(10.0), new DiscountPolicy(15.0)));
    }

    // Control: Hibernate writes a policy only when it differs from the loaded one, so two policies with
    // different rules must never be equal, or a change to the rules would be silently dropped.
    @ParameterizedTest(name = "{0} vs {1}")
    @MethodSource("differentPolicies")
    void GivenPoliciesWithDifferentRules_WhenCompared_ThenTheyDiffer(Object policy, Object other) {
        assertNotEquals(policy, other);
        assertNotEquals(other, policy);
    }
}
