package com.ticketing.system.governance.application.service;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.identity.domain.Admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.shared.exception.SystemIntegrityViolationException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.shared.InvariantChecked;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

import lombok.extern.slf4j.Slf4j;

/**
 * Verifies the platform's structural correctness against persisted state, invoked from
 * {@code SystemAdminService.initializePlatform()} after the I.1 post-conditions hold.
 *
 * <p>This javadoc is the single coverage map for <i>every</i> correctness/integrity requirement
 * in {@code requirements.md} (§1 correctness constraints, §2 integrations, §3 commercial rules,
 * and the relevant SLRs). Only <b>scannable structural state</b> is checked in this class;
 * connection rules are checked at initialization, and behavioural / transaction rules are
 * enforced at their call sites — cited below so nothing is silently dropped.
 *
 * <h3>Scanned here (structural state)</h3>
 * <ul>
 *   <li><b>§1 #6 active company has ≥1 Owner — VERIFIED.</b></li>
 *   <li><b>§1 #4 producer (owner/manager) is a registered user — VERIFIED.</b></li>
 *   <li>§1 #1 unique username, #8 ≤1 active order/buyer/event, #12 sellable ≤ inventory, and the
 *       II.4.8.3 appointment tree (single appointer, no ownership cycles) — all structural but
 *       need aggregate enumeration the V1 repos do not expose → V3 scan.</li>
 * </ul>
 *
 * <h3>Checked at initialization (connection rules)</h3>
 * <ul>
 *   <li>§1 #2 ≥1 System Admin, §2 ≥1 payment + ≥1 ticket-issuance connection — asserted by
 *       {@code SystemAdminService.verifyInitializationInvariants()}.</li>
 * </ul>
 *
 * <h3>Enforced at their call sites (behavioural / transaction rules — not scannable state)</h3>
 * <ul>
 *   <li>§1 #5 auth-on-write, #9 reservation lock, #10 atomic checkout, #11 exclusive order
 *       ownership — per-operation guards in the reservation/checkout services.</li>
 *   <li>§3 #1 charge only after the transaction confirms, #2 funds only as a result of a purchase,
 *       #3 purchase succeeds only after BOTH payment AND issuance, #4 auto-refund + notify on a
 *       broken atomic checkout — {@code CheckoutService} + the UC-4 refund pipeline.</li>
 *   <li>§3 #5 state law: mandatory receipts ({@code OrderReceipt}); anti-scalping + under-18 limits
 *       (purchase policy at checkout).</li>
 *   <li>SLR.1 no double-sold seat — per-aggregate locking; SLR.2 privacy — DTO boundary + BCrypt.</li>
 * </ul>
 *
 * <h3>Deliberately not upheld</h3>
 * <ul>
 *   <li>§1 #3 admin is a Member — the team models admins as a disjoint pool (documented override).</li>
 *   <li>§1 #7 company default policy — satisfied by construction ({@code NoPurchasePolicy} default).</li>
 * </ul>
 *
 * <p><b>V1/V2 note:</b> the in-memory repos are empty when this runs at boot (runner is
 * {@code @Order(0)}, before the dev seeders), so the scans pass trivially today; their value is
 * realized in V3 when persisted state is loaded and re-validated on startup (SLR.6 / SLR.7). The
 * logic is proven by unit tests now, and this class is the growth point for the V3 scans.
 */
@Service
@Slf4j
public class SystemIntegrityVerifier {

    private final UserRepository userRepository;
    private final ProductionCompanyRepository companyRepository;
    private final EventRepository eventRepository;
    private final ActiveOrderRepository activeOrderRepository;

    public SystemIntegrityVerifier(UserRepository userRepository,
                                   ProductionCompanyRepository companyRepository,
                                   EventRepository eventRepository,
                                   ActiveOrderRepository activeOrderRepository) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.eventRepository = eventRepository;
        this.activeOrderRepository = activeOrderRepository;
    }

    // Read-only inside a transaction so the loaded JPA aggregates' lazy collections (event zones,
    // cart items, show dates, …) stay reachable while the scans traverse them (#372).
    @Transactional(readOnly = true)
    public void verify() {
        verifyActiveCompaniesHaveOwner();      // constraint #6
        verifyProducersAreUsers();             // constraint #4
        revalidateLoadedAggregates();          // #372 — re-run each loaded aggregate's own invariants
        verifyUniqueUsernames();               // constraint #1
        verifyOneActiveOrderPerBuyerEvent();   // constraint #8
        verifyInventoryWithinCapacity();       // constraint #12
        log.info("System integrity verified.");
    }

    // #6 — every active production company has at least one Owner.
    private void verifyActiveCompaniesHaveOwner() {
        for (ProductionCompany company : companyRepository.findActive()) {
            if (company.getOwnerIds().isEmpty()) {
                throw new SystemIntegrityViolationException(
                        "active company '" + company.getName() + "' has no owner");
            }
        }
    }

    // #4 — every producer (owner or manager) of an active company resolves to a known user.
    private void verifyProducersAreUsers() {
        for (ProductionCompany company : companyRepository.findActive()) {
            List<Integer> producerIds = new ArrayList<>(company.getOwnerIds());
            producerIds.addAll(company.getManagers());
            for (int userId : producerIds) {
                try {
                    userRepository.getUserById(userId);
                } catch (UserNotFoundException e) {
                    throw new SystemIntegrityViolationException(
                            "company '" + company.getName() + "' has producer " + userId
                                    + " that is not a registered user");
                }
            }
        }
    }

    // ---- #372 V3 scans: re-validate the LOADED DB state (no-ops on the empty in-memory stack) ----

    // Re-run every loaded aggregate's own invariant. A row persisted (or hand-edited) into a state
    // that breaks its aggregate invariant blocks the market from opening.
    private void revalidateLoadedAggregates() {
        revalidateEach(userRepository.findAll(), "user");
        revalidateEach(companyRepository.findAll(), "company");
        revalidateEach(eventRepository.findAll(), "event");
        revalidateEach(activeOrderRepository.findAll(), "active order");
    }

    private void revalidateEach(List<? extends InvariantChecked> aggregates, String type) {
        for (InvariantChecked aggregate : aggregates) {
            try {
                aggregate.checkInvariants();
            } catch (RuntimeException e) {
                // checkInvariants throws a plain IllegalStateException; wrap it as a DomainException so
                // PlatformInitializationRunner's catch leaves the platform uninitialized gracefully.
                throw new SystemIntegrityViolationException(
                        "loaded " + type + " violates its invariant: " + e.getMessage());
            }
        }
    }

    // #1 — usernames are system-wide unique. The column has no DB UNIQUE constraint (unlike email),
    // so a duplicate can physically exist; flag it.
    private void verifyUniqueUsernames() {
        Set<String> seen = new HashSet<>();
        for (User user : userRepository.findAll()) {
            String username = user.getUsername();
            if (username != null && !seen.add(username)) {
                throw new SystemIntegrityViolationException(
                        "duplicate username '" + username + "' in the member pool");
            }
        }
    }

    // #8 — at most one active order per buyer per event. A buyer is keyed by userId (member) or
    // sessionId (guest); flag a buyer whose two distinct orders both hold the same event.
    private void verifyOneActiveOrderPerBuyerEvent() {
        Map<String, Map<Integer, String>> orderByBuyerEvent = new HashMap<>();
        for (ActiveOrder order : activeOrderRepository.findAll()) {
            String buyer = buyerKey(order);
            if (buyer == null) {
                continue; // an order with no identity is caught by the aggregate's own checkInvariants
            }
            Map<Integer, String> byEvent = orderByBuyerEvent.computeIfAbsent(buyer, b -> new HashMap<>());
            Set<Integer> eventIds = new HashSet<>();
            for (CartLineItem item : order.getItems()) {
                eventIds.add(item.geteventId());
            }
            for (Integer eventId : eventIds) {
                String previous = byEvent.put(eventId, order.getOrderKey());
                if (previous != null && !previous.equals(order.getOrderKey())) {
                    throw new SystemIntegrityViolationException(
                            "buyer " + buyer + " has more than one active order for event " + eventId);
                }
            }
        }
    }

    private static String buyerKey(ActiveOrder order) {
        Integer userId = order.userIdOrNull();
        if (userId != null) {
            return "user:" + userId;
        }
        String sessionId = order.getSessionId();
        return sessionId == null ? null : "sess:" + sessionId;
    }

    // #12 — a zone's committed tickets (sold + reserved) must not exceed its physical capacity.
    private void verifyInventoryWithinCapacity() {
        for (Event event : eventRepository.findAll()) {
            if (event.getVenueMap() == null || event.getVenueMap().getInventoryZones() == null) {
                continue;
            }
            for (InventoryZone zone : event.getVenueMap().getInventoryZones()) {
                int committed = zone.getSoldAmount() + zone.getReservedAmount();
                if (committed > zone.getCapacity()) {
                    throw new SystemIntegrityViolationException(
                            "event " + event.getId() + " has an oversold zone: sold+reserved="
                                    + committed + " > capacity=" + zone.getCapacity());
                }
            }
        }
    }
}
