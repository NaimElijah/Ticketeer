package com.ticketing.system.governance.application.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
 * Verifies the platform's structural correctness against persisted state. {@code SystemAdminService}
 * invokes it from {@code initializePlatform()} and each time the market opens (from READY or CLOSED),
 * after its System Admin check.
 *
 * <p>This javadoc is the single coverage map for <i>every</i> correctness/integrity requirement
 * in {@code docs/requirements/requirements.md} (§1 correctness constraints, §2 integrations,
 * §3 commercial rules, and the relevant SLRs). Only <b>scannable structural state</b> is checked
 * in this class; connection rules are checked at initialization, and behavioural / transaction
 * rules are enforced at their call sites — cited below so nothing is silently dropped.
 *
 * <h3>Scanned here (structural state)</h3>
 * <ul>
 *   <li><b>§1 #6 active company has ≥1 Owner — VERIFIED.</b></li>
 *   <li><b>§1 #4 producer (owner/manager) is a registered user — VERIFIED.</b></li>
 *   <li><b>§1 #1 unique username, #8 ≤1 active order/member/event, #12 sellable ≤ inventory —
 *       VERIFIED.</b> Guest carts are left out of #8: a pre-V10 instance serving during a rolling
 *       deploy can still leave two on one session id (NaimElijah/Ticketeer#224, V10's header), and this
 *       scan's failure stops the boot. NaimElijah/Ticketeer#275 scans them once no such instance can
 *       serve.</li>
 *   <li>The II.4.8.3 appointment tree (single appointer, no ownership cycles) is structural but
 *       not scanned, and owner revocation can currently break it (#126).</li>
 * </ul>
 *
 * <h3>Checked at initialization and market open (connection rules)</h3>
 * <ul>
 *   <li>§1 #2 ≥1 System Admin — asserted by {@code SystemAdminService.verifyInitializationInvariants()}
 *       at initialization and by an equivalent check when the market opens;
 *       §2 ≥1 payment + ≥1 ticket-issuance connection — asserted last at initialization, and first when
 *       the market opens, by {@code SystemAdminService.requireExternalServicesReachable()} (ADR-003).</li>
 * </ul>
 *
 * <h3>Enforced at their call sites (behavioural / transaction rules — not scannable state)</h3>
 * <ul>
 *   <li>§1 #5 auth-on-write, #9 reservation lock, #11 exclusive order ownership — per-operation
 *       guards in the reservation/checkout services; #10 an order unfinished when its hold expires
 *       is cancelled — {@code SessionAndOrderSweeper}.</li>
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
 *   <li>§1 #7 default policies — not scanned: the purchase-policy default holds by construction
 *       ({@code NoPurchasePolicy}); a company discount policy is not built yet (NaimElijah/Ticketeer#67).</li>
 * </ul>
 *
 * <p>The scans read whatever the repositories hold when they run (SLR.6 / SLR.7). On the default
 * profile that is the PostgreSQL data of earlier runs, loaded and re-validated at boot. They find
 * nothing to check on an empty database: a first start, or dev's in-memory H2 at boot, where the
 * runner ({@code @Order(0)}) runs before the dev seeders.
 */
@Service
@Slf4j
public class SystemIntegrityVerifier {

    private final UserRepository userRepository;
    private final ProductionCompanyRepository companyRepository;
    private final EventRepository eventRepository;
    private final ActiveOrderRepository activeOrderRepository;

    /** Creates the verifier over the repositories whose persisted state it scans. */
    public SystemIntegrityVerifier(UserRepository userRepository,
                                   ProductionCompanyRepository companyRepository,
                                   EventRepository eventRepository,
                                   ActiveOrderRepository activeOrderRepository) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.eventRepository = eventRepository;
        this.activeOrderRepository = activeOrderRepository;
    }

    /**
     * Scans the persisted state for a break of the structural correctness constraints listed on the
     * class, and returns normally when it finds none.
     *
     * <p>The scans run in this order and stop at the first violation: every active company has an
     * owner; every owner and manager of an active company is a registered user; every loaded user,
     * company, event and active order passes its own invariant check; usernames are unique; no member
     * holds two active orders for one event; no zone has more sold and reserved tickets than its
     * capacity.
     *
     * <p>Read-only: the scans never write. The one transaction also keeps the loaded aggregates
     * attached while the scans traverse them, which matters only for an association mapped LAZY; every
     * association is EAGER today (#372).
     *
     * @throws com.ticketing.system.shared.exception.SystemIntegrityViolationException at the first
     *         violation. The message names the offending records by id; for a broken aggregate
     *         invariant the cause is the aggregate's own exception
     */
    @Transactional(readOnly = true)
    public void verify() {
        verifyActiveCompaniesHaveOwner();      // constraint #6
        verifyProducersAreUsers();             // constraint #4
        revalidateLoadedAggregates();          // #372 — re-run each loaded aggregate's own invariants
        verifyUniqueUsernames();               // constraint #1
        verifyOneActiveOrderPerMemberEvent();  // constraint #8, members only
        verifyInventoryWithinCapacity();       // constraint #12
        log.info("System integrity verified.");
    }

    // Every message below names its records by id, never by a company name or a username: the self-heal
    // logs a violation at ERROR on every tick while it lasts.

    // #6 — every active production company has at least one Owner.
    private void verifyActiveCompaniesHaveOwner() {
        for (ProductionCompany company : companyRepository.findActive()) {
            if (company.getOwnerIds().isEmpty()) {
                throw new SystemIntegrityViolationException(
                        "active company " + company.getCompanyId() + " has no owner");
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
                            "company " + company.getCompanyId() + " has producer " + userId
                                    + " that is not a registered user", e);
                }
            }
        }
    }

    // ---- #372: re-validate the state loaded from the repositories ----

    // Re-run every loaded aggregate's own invariant. A row persisted (or hand-edited) into a state
    // that breaks its aggregate invariant blocks the market from opening.
    private void revalidateLoadedAggregates() {
        revalidateEach(userRepository.findAll(), "user", User::getUserId);
        revalidateEach(companyRepository.findAll(), "company", ProductionCompany::getCompanyId);
        revalidateEach(eventRepository.findAll(), "event", Event::getId);
        revalidateEach(activeOrderRepository.findAll(), "active order", ActiveOrder::getOrderKey);
    }

    // The id names the row to repair; the cause keeps the invariant's own stack trace.
    private <T extends InvariantChecked> void revalidateEach(List<T> aggregates, String type,
                                                             Function<? super T, ?> idOf) {
        for (T aggregate : aggregates) {
            try {
                aggregate.checkInvariants();
            } catch (RuntimeException e) {
                // checkInvariants throws a plain IllegalStateException: the wrap gives the failure
                // a domain type and keeps it as the cause. At boot it stops the application; before
                // a later open it keeps the market closed (ADR-003).
                throw new SystemIntegrityViolationException(
                        "loaded " + type + " " + idOf.apply(aggregate) + " violates its invariant: "
                                + e.getMessage(), e);
            }
        }
    }

    // #1 — usernames are system-wide unique. On the Jpa adapters uk_users_username (V4) already
    // forbids a duplicate; this scan still covers the Memory adapter and any row written around the
    // adapters.
    private void verifyUniqueUsernames() {
        Map<String, Integer> userIdByUsername = new HashMap<>();
        for (User user : userRepository.findAll()) {
            String username = user.getUsername();
            if (username == null) {
                continue;
            }
            Integer firstUserId = userIdByUsername.putIfAbsent(username, user.getUserId());
            if (firstUserId != null) {
                throw new SystemIntegrityViolationException(
                        "users " + firstUserId + " and " + user.getUserId() + " share one username");
            }
        }
    }

    // #8 — at most one active order per member per event; flag a member whose two distinct orders
    // both hold the same event. Guest carts are skipped: uk_active_orders_guest_session_id (V10,
    // NaimElijah/Ticketeer#224) holds a session to one, except the carts a pre-V10 instance writes during
    // a rolling deploy, which the sweeper clears (requirements.md §1 #8), and failing here would stop the
    // boot (ADR-003) and block reopening the market. NaimElijah/Ticketeer#275 scans guests once no such
    // instance can serve.
    private void verifyOneActiveOrderPerMemberEvent() {
        Map<Integer, Map<Integer, String>> orderByMemberEvent = new HashMap<>();
        for (ActiveOrder order : activeOrderRepository.findAll()) {
            Integer memberId = order.userIdOrNull();
            if (memberId == null) {
                continue;
            }
            Map<Integer, String> byEvent = orderByMemberEvent.computeIfAbsent(memberId, m -> new HashMap<>());
            Set<Integer> eventIds = new HashSet<>();
            for (CartLineItem item : order.getItems()) {
                eventIds.add(item.geteventId());
            }
            for (Integer eventId : eventIds) {
                String previous = byEvent.put(eventId, order.getOrderKey());
                if (previous != null && !previous.equals(order.getOrderKey())) {
                    // Name the carts by order key, never by session id: a session id is a credential,
                    // and LoggingAspect logs this message at WARN.
                    throw new SystemIntegrityViolationException(
                            "member " + memberId + " has more than one active order for event " + eventId
                                    + ": orders " + previous + " and " + order.getOrderKey());
                }
            }
        }
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
