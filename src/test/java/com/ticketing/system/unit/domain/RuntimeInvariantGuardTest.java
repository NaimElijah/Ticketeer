package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Domain.ActiveOrder.ActiveOrder;
import com.ticketing.system.Core.Domain.Tickets.Ticket;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.Core.Domain.users.CompanyAppointment;
import com.ticketing.system.Core.Domain.users.Permission;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.support.BaseDomainTest;

/**
 * Regression coverage for the "invariants hold at runtime, not just at construction"
 * work: every state-mutating method re-asserts {@link
 * com.ticketing.system.shared.InvariantChecked#checkInvariants()}, and
 * collection getters hand back defensive copies so external code cannot corrupt an
 * aggregate behind its own back.
 *
 * <p>Aggregates that are deliberately driven into a rejected state are NOT registered
 * via {@link #track(Object)} — the mutator throws <em>after</em> writing the field, so
 * the object is left invalid on purpose and the {@code @AfterEach} harness would
 * (correctly) flag it.
 */
class RuntimeInvariantGuardTest extends BaseDomainTest {

    private static final int COMPANY_ID = 100;
    private static final int OWNER_ID = 1;
    private static final int TARGET_ID = 3;
    private static final int INVITER_ID = 1;

    // --- Pattern C: raw bypass setter now re-validates ---------------------------

    @Test
    void setPermissions_emptySetOnManager_throwsAndRollsBack() {
        CompanyAppointment manager = track(CompanyAppointment.ManagerAppointment(
                7, COMPANY_ID, TARGET_ID, INVITER_ID, List.of(Permission.MANAGE_INVENTORY)));

        // Manager must keep at least one permission — the raw setter used to allow this.
        assertThrows(IllegalStateException.class,
                () -> manager.setPermissions(EnumSet.noneOf(Permission.class)));

        // Validate-before-commit: the rejected call rolled back, leaving the original permission.
        assertTrue(manager.getPermissions().contains(Permission.MANAGE_INVENTORY));
    }

    // --- Pattern A: status mutation re-asserts invariants ------------------------

    @Test
    void addManager_whenTargetIsAlreadyOwner_throwsAndLeavesManagersUnchanged() {
        ProductionCompany company = track(new ProductionCompany(
                COMPANY_ID, OWNER_ID, "Acme", CompanyStatus.ACTIVE, "desc", 4.5));

        // OWNER_ID is the founder/owner — the owner/manager-overlap invariant forbids this.
        assertThrows(RuntimeException.class, () -> company.addManager(OWNER_ID));

        // Validate-before-commit: the failed add rolled back, managers stays empty.
        assertFalse(company.getManagers().contains(OWNER_ID));
        assertTrue(company.isOwner(OWNER_ID));
    }

    @Test
    void clearSession_onMemberCart_succeeds() {
        // Member cart keeps userId after the session pointer clears — still valid.
        ActiveOrder memberCart = track(ActiveOrder.forMember(42, "session-xyz"));

        memberCart.clearSession();

        assertTrue(memberCart.isMember());
        assertEquals(42, memberCart.getUserId());
    }

    // --- Pattern B: defensive copies on leaky getters ----------------------------

    @Test
    void getManagers_returnsDefensiveCopy_externalMutationDoesNotLeak() {
        ProductionCompany company = track(new ProductionCompany(
                COMPANY_ID, OWNER_ID, "Acme", CompanyStatus.ACTIVE, "desc", 4.5));
        company.addManager(TARGET_ID);

        List<Integer> managers = company.getManagers();
        managers.clear(); // mutate the returned copy

        assertTrue(company.getManagers().contains(TARGET_ID),
                "internal managers list must be unaffected by mutating the returned copy");
    }

    @Test
    void getOwnersIds_returnsDefensiveCopy_externalMutationDoesNotLeak() {
        ProductionCompany company = track(new ProductionCompany(
                COMPANY_ID, OWNER_ID, "Acme", CompanyStatus.ACTIVE, "desc", 4.5));

        List<Integer> owners = company.getOwnersIds();
        owners.clear(); // attempt to strip owners (incl. the founder) from outside

        assertFalse(company.getOwnersIds().isEmpty(),
                "internal owners list must be unaffected by mutating the returned copy");
        assertTrue(company.isOwner(OWNER_ID));
    }

    // --- More validate-before-commit rollbacks -----------------------------------

    @Test
    void session_touchBeforeCreatedAt_throwsAndRollsBack() {
        Instant createdAt = Instant.now();
        Session session = track(new Session("sess-1", 1, createdAt, createdAt.plusSeconds(3600)));

        // lastSeenAt must stay >= createdAt — a backwards clock value must be rejected.
        assertThrows(IllegalStateException.class, () -> session.touch(createdAt.minusSeconds(60)));

        // Rolled back: lastSeenAt is still the original createdAt, not the rejected value.
        assertEquals(createdAt, session.getLastSeenAt());
    }

    @Test
    void ticket_setTicketIdNonPositive_throwsAndRollsBack() {
        Ticket ticket = track(new Ticket(1, 0, 1, null, 10.0, 5, "BC-1"));

        assertThrows(IllegalStateException.class, () -> ticket.setTicketId(0));

        // Rolled back: the ticket keeps its original positive id.
        assertEquals(5, ticket.getId());
    }

    @Test
    void setRating_outOfRange_throwsAndRollsBack() {
        ProductionCompany company = track(new ProductionCompany(
                COMPANY_ID, OWNER_ID, "Acme", CompanyStatus.ACTIVE, "desc", 4.5));

        // Rating must stay within 0–5 — an out-of-range value must be rejected.
        assertThrows(IllegalStateException.class, () -> company.setRating(6.0));

        // Rolled back: the company keeps its original valid rating.
        assertEquals(4.5, company.getRating());
    }
}
