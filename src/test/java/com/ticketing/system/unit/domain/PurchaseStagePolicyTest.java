package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MinTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchaseContext;
import com.ticketing.system.shared.domain.policy.PurchaseStage;

/**
 * V2-POL-05 (#220): purchase-policy rules must behave differently at the RESERVE
 * stage (cart still being built) vs. the final CHECKOUT.
 */
class PurchaseStagePolicyTest {

    private static PurchaseContext ctx(Integer age, int quantity, PurchaseStage stage) {
        return new PurchaseContext(1, age, 10, 100, quantity, stage);
    }

    @Test
    void minTickets_skippedAtReserve_enforcedAtCheckout() {
        MinTicketsPurchasePolicy min = new MinTicketsPurchasePolicy(2);

        assertTrue(min.isSatisfiedBy(ctx(25, 1, PurchaseStage.RESERVE)),
                "minimum is not enforced while the cart is still being built");
        assertFalse(min.isSatisfiedBy(ctx(25, 1, PurchaseStage.CHECKOUT)),
                "minimum is enforced at checkout");
        assertTrue(min.isSatisfiedBy(ctx(25, 2, PurchaseStage.CHECKOUT)),
                "minimum met at checkout");
    }

    @Test
    void age_unknownSkippedAtReserve_failsAtCheckout() {
        AgePurchasePolicy age = new AgePurchasePolicy(18);

        assertTrue(age.isSatisfiedBy(ctx(null, 1, PurchaseStage.RESERVE)),
                "a guest's unknown age is deferred to checkout at reserve time");
        assertFalse(age.isSatisfiedBy(ctx(null, 1, PurchaseStage.CHECKOUT)),
                "an unknown age fails at checkout");
    }

    @Test
    void age_memberEnforcedAtBothStages() {
        AgePurchasePolicy age = new AgePurchasePolicy(18);

        assertFalse(age.isSatisfiedBy(ctx(16, 1, PurchaseStage.RESERVE)),
                "an under-age member is rejected already at reserve");
        assertTrue(age.isSatisfiedBy(ctx(20, 1, PurchaseStage.RESERVE)),
                "an of-age member is allowed at reserve");
        assertFalse(age.isSatisfiedBy(ctx(16, 1, PurchaseStage.CHECKOUT)),
                "an under-age member is rejected at checkout");
    }

    @Test
    void maxTickets_enforcedAtBothStages() {
        MaxTicketsPurchasePolicy max = new MaxTicketsPurchasePolicy(3);

        assertFalse(max.isSatisfiedBy(ctx(25, 4, PurchaseStage.RESERVE)),
                "over the maximum is rejected at reserve");
        assertFalse(max.isSatisfiedBy(ctx(25, 4, PurchaseStage.CHECKOUT)),
                "over the maximum is rejected at checkout");
        assertTrue(max.isSatisfiedBy(ctx(25, 3, PurchaseStage.RESERVE)),
                "at the maximum is allowed");
    }

    @Test
    void purchaseContext_defaultStageIsCheckout() {
        assertEquals(PurchaseStage.CHECKOUT, new PurchaseContext(1, 25, 10, 100, 1).getStage(),
                "the existing 5-arg constructor must keep enforcing every rule (checkout)");
    }
}
