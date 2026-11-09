package com.ticketing.system.governance.application.service;
import com.ticketing.system.governance.application.service.SystemIntegrityVerifier;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.shared.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dtoMappers.OrderReceiptMapper;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.sales.application.port.out.TicketIssuer;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.shared.exception.InitializationIntegrityException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.MissingDefaultAdminException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the platform lifecycle and market state, and the admin's global purchase history: UC-1
 * (initialize), UC-31 (global history) and UC-32 (open and close the market).
 *
 * <p>The lifecycle is an in-memory state machine, {@code UNINITIALIZED -> READY -> OPEN <-> CLOSED}. It
 * is not persisted, so every start begins at {@code UNINITIALIZED}. Sales read it through
 * {@link #isMarketOpen()}. {@link #openMarket}, {@link #closeMarket}, {@link #viewMarketState} and
 * {@link #viewGlobalHistory} authorize the caller with an admin token on every call.
 *
 * <p>The transitions are synchronized and the state is volatile, so readers see the latest state
 * without waiting. {@link #refreshServiceReachability()} is deliberately not synchronized.
 */
@Service
@Slf4j
public class SystemAdminService {

    private final SessionManager sessionManager;
    private final AdminRepository adminRepository;
    private final OrderReceiptRepository orderReceiptRepository;
    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    // Catalog display port for resolving event/zone names in global-history records (via OrderReceiptMapper).
    private final CatalogEventDisplayPort eventDisplayPort;
    private final ProductionCompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final List<PaymentGateway> paymentGateways;
    private final List<TicketIssuer> ticketIssuers;
    private final PasswordHasher passwordHasher;
    private final SystemIntegrityVerifier integrityVerifier;

    // UC-1 / I.1.4 — default System Admin credentials. Bound from platform.admin.* in
    // application.yml (env-overridable); never hardcoded here.
    private final String defaultAdminUsername;
    private final String defaultAdminPassword;
    private static final int DEFAULT_ADMIN_ID = 1;

    // Platform lifecycle state: in memory, not persisted. openMarket()/closeMarket() (UC-32, upstream#307)
    // build on top of this status.
    private enum PlatformStatus { UNINITIALIZED, READY, OPEN, CLOSED }
    private volatile PlatformStatus status = PlatformStatus.UNINITIALIZED;
    private volatile LocalDateTime lastInitializedAt;
    private volatile LocalDateTime lastOpenedAt;
    // The last WSEP probe (init, open, every self-heal tick). The market view reports it instead of
    // probing: against a hanging WSEP a probe takes about two minutes (two handshakes of up to three
    // 20-second attempts each), and the dashboard polls every 5 s.
    // One immutable snapshot, so a reader never pairs one probe's flags with another's time.
    private record Reachability(boolean payment, boolean issuer, LocalDateTime probedAt) { }
    private static final Reachability NOT_CHECKED = new Reachability(false, false, null);
    private volatile Reachability lastProbe = NOT_CHECKED;
    // Stamps the lifecycle transitions above: UTC wall time (ADR-008).
    private final Clock clock;

    /**
     * Creates the service in the {@code UNINITIALIZED} state; nothing is probed or stored until
     * {@link #initializePlatform()} runs.
     *
     * @param paymentGateways the payment services probed for reachability; the platform needs at least
     *                        one of them to answer
     * @param ticketIssuers   the ticket-issuing services probed for reachability; at least one must
     *                        answer
     * @param platformAdmin   the credentials the default System Admin is created with
     * @param clock           stamps the lifecycle transitions and probes
     */
    public SystemAdminService(
            SessionManager sessionManager,
            AdminRepository adminRepository,
            OrderReceiptRepository orderReceiptRepository,
            TicketRepository ticketRepository,
            EventRepository eventRepository,
            CatalogEventDisplayPort eventDisplayPort,
            ProductionCompanyRepository companyRepository,
            UserRepository userRepository,
            List<PaymentGateway> paymentGateways,
            List<TicketIssuer> ticketIssuers,
            PasswordHasher passwordHasher,
            SystemIntegrityVerifier integrityVerifier,
            PlatformAdminProperties platformAdmin,
            Clock clock
    ) {
        this.sessionManager = sessionManager;
        this.adminRepository = adminRepository;
        this.orderReceiptRepository = orderReceiptRepository;
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.eventDisplayPort = eventDisplayPort;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.paymentGateways = paymentGateways;
        this.ticketIssuers = ticketIssuers;
        this.passwordHasher = passwordHasher;
        this.integrityVerifier = integrityVerifier;
        this.defaultAdminUsername = platformAdmin.username();
        this.defaultAdminPassword = platformAdmin.password();
        this.clock = clock;
    }

    /**
     * UC-1: brings the platform from {@code UNINITIALIZED} to {@code READY}, a healthy,
     * market-openable state. In order, it creates the default System Admin if none exists (I.1.4),
     * re-asserts that an admin exists and runs {@link SystemIntegrityVerifier} (I.1.1), then requires at
     * least one reachable payment service and one reachable ticket issuer (I.1.2, I.1.3).
     *
     * <p>The fatal checks run before the WSEP check on purpose: a WSEP outage is the only failure
     * expected to self-heal (ADR-003), so checking it first would hide a missing admin or corrupt data
     * behind every retry. The probe result is recorded either way; a success also records the
     * initialization time.
     *
     * <p>Idempotent: in any state other than {@code UNINITIALIZED} the call logs and returns without
     * checking anything. A failed call leaves the state {@code UNINITIALIZED}, so it can be repeated.
     *
     * @throws MissingDefaultAdminException if the default admin cannot be created
     * @throws InitializationIntegrityException if no System Admin exists after that step
     * @throws com.ticketing.system.shared.exception.SystemIntegrityViolationException if the persisted
     *         state breaks a structural correctness constraint
     * @throws ExternalServiceUnavailableException if no payment service, or no ticket issuer, answers
     */
    public synchronized void initializePlatform() {
        log.info("Platform initialization requested.");

        if (status != PlatformStatus.UNINITIALIZED) {
            log.info("Platform already initialized (status={}); ignoring re-initialization.", status);
            return;
        }

        // ADR-003: the fatal checks run first. A WSEP outage is the only failure that self-heals,
        // so checking WSEP first would hide a missing admin or corrupt data behind every retry.

        // I.1.4 — guarantee at least one System Admin (auto-create a default if none).
        createDefaultAdminIfMissing();

        // Step 4 / I.1.1 — re-assert that an admin exists, then the system-wide structural
        // correctness constraints, as explicit gates before going live.
        verifyInitializationInvariants();
        integrityVerifier.verify();

        // I.1.2 / I.1.3 — at least one payment service and one ticket-issuance service reachable.
        requireExternalServicesReachable();

        this.lastInitializedAt = LocalDateTime.now(clock);
        this.status = PlatformStatus.READY;
        log.info("Platform initialized — status READY.");
    }

    /**
     * UC-1 / I.1.4: creates the default System Admin if no admin exists. A save that loses to another
     * instance creating it first is not a failure (ADR-003).
     *
     * @throws MissingDefaultAdminException if creating or saving the admin fails and the re-check that
     *         follows finds none (its cause is the creation failure; a failure of the re-check is
     *         attached as suppressed), or if the save returns but no admin exists afterwards (no cause)
     */
    public void createDefaultAdminIfMissing() {
        if (adminRepository.existsAny()) {
            log.info("A System Admin already exists; no default needed.");
            return;
        }

        // No admin password can be changed from the platform yet (#77), so the message promises no rotation.
        log.warn("No System Admin found — creating default admin '{}' with the password configured in "
                + "platform.admin.password (PLATFORM_ADMIN_PASSWORD).", defaultAdminUsername);
        try {
            Admin defaultAdmin = new Admin(
                    DEFAULT_ADMIN_ID,
                    defaultAdminUsername,
                    passwordHasher.hash(defaultAdminPassword),
                    true);
            defaultAdmin.checkInvariants();
            adminRepository.save(defaultAdmin);
        } catch (RuntimeException saveFailure) {
            MissingDefaultAdminException failure =
                    new MissingDefaultAdminException(saveFailure.getMessage(), saveFailure);
            // Another instance booting against the same database may have inserted it first. An admin
            // then exists, so the lost insert is not an init failure and must not stop this boot (ADR-003).
            boolean createdConcurrently;
            try {
                createdConcurrently = adminRepository.existsAny();
            } catch (RuntimeException reCheckFailure) {
                failure.addSuppressed(reCheckFailure);
                throw failure;
            }
            if (createdConcurrently) {
                log.info("The default System Admin was created concurrently; continuing.");
                return;
            }
            throw failure;
        }

        // Confirm the write actually took — a silent persistence failure must fail init.
        if (!adminRepository.existsAny()) {
            throw new MissingDefaultAdminException("default admin was not persisted");
        }
    }

    /**
     * UC-32 (upstream#9, I.2.1): opens the trading market so reservations and checkouts are accepted.
     * Admin-only.
     *
     * <p>Re-verifies both external services (I.2.2), then that a System Admin exists, then the
     * structural integrity ({@link SystemIntegrityVerifier}), before moving to {@code OPEN} and
     * recording the time. Opens from {@code READY} and re-opens from {@code CLOSED}. Idempotent on an
     * open market: once the caller is authorized, it returns the current state without re-verifying
     * the services or the integrity.
     *
     * @param request carries the admin token; only its {@code token} is read. A {@code null} request is
     *                treated as a missing token
     * @return the market state after the call
     * @throws UnauthorizedActionException if the token is missing, or names an admin no longer on record
     * @throws com.ticketing.system.shared.exception.InvalidTokenException for a malformed, revoked or
     *         member's token
     * @throws com.ticketing.system.shared.exception.SessionExpiredException for an expired token
     * @throws MarketNotOpenException if the platform is still {@code UNINITIALIZED}
     * @throws ExternalServiceUnavailableException if no payment service, or no ticket issuer, answers
     * @throws InitializationIntegrityException if no System Admin exists
     * @throws com.ticketing.system.shared.exception.SystemIntegrityViolationException if the persisted
     *         state breaks a structural correctness constraint
     */
    public synchronized MarketStateDTO openMarket(MarketControlRequestDTO request) {
        requireSystemAdmin(tokenOf(request));
        return doOpenMarket();
    }

    // Core market-open logic, shared by the admin-gated openMarket() and the system-internal
    // ensureMarketOpen() recovery hook. Re-verifies both external services + structural invariants
    // before flipping to OPEN; idempotent on an already-open market; re-opens from CLOSED; rejects an
    // UNINITIALIZED platform.
    private MarketStateDTO doOpenMarket() {
        if (status == PlatformStatus.OPEN) {
            log.info("Market already open; ignoring open request.");
            return buildMarketState();
        }
        if (status == PlatformStatus.UNINITIALIZED) {
            throw new MarketNotOpenException("platform not initialized");
        }

        // I.2.2 — re-verify both external services at open time; don't flip state if either is down.
        requireExternalServicesReachable();

        // I.2.1 — re-assert structural invariants (>=1 admin + system-wide integrity) before going live.
        if (!adminRepository.existsAny()) {
            throw new InitializationIntegrityException("no System Admin present");
        }
        integrityVerifier.verify();

        this.status = PlatformStatus.OPEN;
        this.lastOpenedAt = LocalDateTime.now(clock);
        log.info("Market opened.");
        return buildMarketState();
    }

    /**
     * System-internal, idempotent recovery hook (upstream#455): brings the market to OPEN if it safely can.
     * Used by the auto-opener and the self-heal scheduler so a transient external-service blip at boot
     * (e.g. a cold-starting WSEP endpoint) doesn't leave the market closed with no recovery (V3 Req 6).
     * Not admin-gated: the platform acts on itself, not on a user request. Respects an admin's
     * deliberate close: never auto-reopens a CLOSED market.
     *
     * <p>Never throws; the caller retries on its own schedule. A WSEP outage, the one failure ADR-003
     * expects to self-heal, is logged at WARN. Any other failure (an integrity violation, a missing
     * admin, a database outage, a bug) is logged at ERROR with its exception.
     *
     * @return {@code true} if this call probed WSEP (an initialization or open got as far as its
     *         reachability check), so a caller that keeps the market view current need not probe again
     */
    public synchronized boolean ensureMarketOpen() {
        if (status == PlatformStatus.OPEN || status == PlatformStatus.CLOSED) {
            return false; // already open, or deliberately closed by an admin — leave it alone
        }
        Reachability probeBefore = lastProbe;
        try {
            if (status == PlatformStatus.UNINITIALIZED) {
                initializePlatform();   // UNINITIALIZED -> READY (the WSEP handshake now retries internally)
            }
            if (status == PlatformStatus.READY) {
                doOpenMarket();         // READY -> OPEN
            }
        } catch (ExternalServiceUnavailableException e) {
            log.warn("Market auto-open is waiting for WSEP (will retry): {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Market auto-open failed (will retry).", e);
        }
        return lastProbe != probeBefore;
    }

    /**
     * Probes the payment and ticket-issuing services and records both results, with the time, for the
     * market view. Called by the self-heal scheduler on every tick in which {@link #ensureMarketOpen()}
     * did not probe, so the view stays current while the market is OPEN or CLOSED.
     *
     * <p>Deliberately not synchronized: against a hanging WSEP a probe takes about two minutes (two
     * handshakes of up to three 20-second attempts each), and neither a close nor a view may wait for
     * it. Two probes that race both record a fresh result. Never throws: an unreachable service is
     * recorded as such.
     */
    public void refreshServiceReachability() {
        probeServices();
    }

    /**
     * UC-32: closes the trading market, an admin and operations incident control. Admin-only. While
     * the market is {@code CLOSED}, reservations and checkouts are refused, and
     * {@link #ensureMarketOpen()} never reopens it while this process runs; only {@link #openMarket}
     * does. The state is not persisted, so a restart loses the close and {@code MarketOpener} opens
     * the market again (NaimElijah/Ticketeer#24). Idempotent on a closed market: it returns the
     * current state.
     *
     * @param request carries the admin token and an optional reason. The reason is only logged, and a
     *                missing or blank one is logged as {@code unspecified}. A {@code null} request is
     *                treated as a missing token
     * @return the market state after the call
     * @throws UnauthorizedActionException if the token is missing, or names an admin no longer on record
     * @throws com.ticketing.system.shared.exception.InvalidTokenException for a malformed, revoked or
     *         member's token
     * @throws com.ticketing.system.shared.exception.SessionExpiredException for an expired token
     * @throws InvalidStateTransitionException if the market was never opened
     */
    public synchronized MarketStateDTO closeMarket(MarketControlRequestDTO request) {
        requireSystemAdmin(tokenOf(request));

        if (status == PlatformStatus.CLOSED) {
            log.info("Market already closed; ignoring close request.");
            return buildMarketState();
        }
        if (status != PlatformStatus.OPEN) {
            throw new InvalidStateTransitionException("market is not open; cannot close");
        }

        this.status = PlatformStatus.CLOSED;
        log.info("Market closed (reason: {}).", reasonOrDefault(request));
        return buildMarketState();
    }

    /**
     * The market/health snapshot for the admin dashboard. The services' reachability and its time are
     * those of the last probe: initialization, open, or a self-heal tick
     * ({@link #refreshServiceReachability()}). Before any probe the time is {@code null} and both flags
     * are {@code false}. This call never probes WSEP. Gated like {@link #openMarket}: the UI's route
     * guard only steers navigation.
     *
     * @param adminToken the caller's admin session token
     * @return the current market state
     * @throws UnauthorizedActionException if the token is missing, or names an admin no longer on record
     * @throws com.ticketing.system.shared.exception.InvalidTokenException for a malformed, revoked or
     *         member's token
     * @throws com.ticketing.system.shared.exception.SessionExpiredException for an expired token
     * @throws RuntimeException a failure of the session store, propagated unchanged
     */
    public MarketStateDTO viewMarketState(String adminToken) {
        requireSystemAdmin(adminToken);
        return buildMarketState();
    }

    /**
     * Whether the market is {@code OPEN}, the read boundary for the private status that the sales
     * gate (reservations and checkout) uses to refuse work while the market is not open
     * (I.2.1 / UC-32). Reads the volatile state, so it never waits for a running probe.
     *
     * @return {@code true} only in the {@code OPEN} state
     */
    public boolean isMarketOpen() {
        return status == PlatformStatus.OPEN;
    }

    // *HELPER* — single builder for the market snapshot. It never probes WSEP: it reports the last probe.
    private MarketStateDTO buildMarketState() {
        boolean adminPresent = adminRepository.existsAny();
        Reachability probe = lastProbe;
        return new MarketStateDTO(
                status.name(),
                lastInitializedAt,
                lastOpenedAt,
                probe.payment(),
                probe.issuer(),
                probe.probedAt(),
                adminPresent);
    }

    // *HELPER* — null-safe accessors for the optional control request.
    private static String tokenOf(MarketControlRequestDTO request) {
        return request == null ? null : request.token();
    }

    private static String reasonOrDefault(MarketControlRequestDTO request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            return "unspecified";
        }
        return request.reason();
    }




    // *HELPER* — I.1.2/I.1.3 & I.2.2 external-service quorum: at least one payment
    // gateway and one ticket issuer must be reachable. Shared by initialize and
    // openMarket so the "service down" failure is identical in both paths. Both kinds are probed
    // before either failure is thrown, so the market view never shows a stale result for one of them.
    private void requireExternalServicesReachable() {
        Reachability probe = probeServices();
        if (!probe.payment()) {
            throw new ExternalServiceUnavailableException("no reachable payment service");
        }
        if (!probe.issuer()) {
            throw new ExternalServiceUnavailableException("no reachable ticket issuance service");
        }
    }

    // Probes both service kinds and records the result with the time it finished.
    private Reachability probeServices() {
        boolean payment = paymentGateways.stream().anyMatch(this::isReachable);
        boolean issuer = ticketIssuers.stream().anyMatch(this::isReachable);
        Reachability probe = new Reachability(payment, issuer, LocalDateTime.now(clock));
        lastProbe = probe;
        return probe;
    }

    // *HELPER* — UC-1 step 4 / I.1.1: re-assert that a System Admin exists. The external-service
    // post-conditions are asserted separately by requireExternalServicesReachable(), which
    // initializePlatform() runs after this and the integrity scan.
    private void verifyInitializationInvariants() {
        if (!adminRepository.existsAny()) {
            throw new InitializationIntegrityException("no System Admin present");
        }
    }

    // *HELPER METHODS* — UC-1 I.1.2/I.1.3 reachability probe (maps to the WSEP `handshake`).
    // A thrown verification (e.g. a real HTTP adapter timing out) is treated as "unreachable"
    // so a flaky provider can never crash bootstrap — it just doesn't count toward the quorum.
    private boolean isReachable(PaymentGateway gateway) {
        try {
            boolean ok = gateway.verifyConnection();
            if (!ok) {
                log.warn("Payment gateway '{}' reported unreachable.", gateway.getId());
            }
            return ok;
        } catch (RuntimeException e) {
            log.warn("Payment gateway '{}' verification failed: {}", gateway.getId(), e.getMessage());
            return false;
        }
    }

    private boolean isReachable(TicketIssuer issuer) {
        try {
            boolean ok = issuer.verifyConnection();
            if (!ok) {
                log.warn("Ticket issuer '{}' reported unreachable.", issuer.getId());
            }
            return ok;
        } catch (RuntimeException e) {
            log.warn("Ticket issuer '{}' verification failed: {}", issuer.getId(), e.getMessage());
            return false;
        }
    }




    /**
     * UC-31: the purchase history across all buyers, companies and events, narrowed by optional
     * filters. Admin-only; the check happens inside, before anything is read.
     *
     * <p>The filters combine: buyer, company, specific events and a date range. A company filter
     * stands for that company's events; given together with event ids, it keeps only the ids that
     * belong to the company and drops the rest without an error. A filter that ends up naming no event
     * at all, such as a company with no events, matches nothing. A {@code null} {@code filters}, or one
     * with every field {@code null}, matches every receipt. When an event filter applies, each record
     * carries only the lines of those events; its total is their sum, and its transactions are still
     * the whole order's. Both dates are inclusive. A record with no tickets is left out.
     *
     * @param token   the caller's admin session token
     * @param filters the filters; may be {@code null}
     * @return a list holding exactly one {@link PurchaseHistoryDTO}, whose records are the matches,
     *         none when nothing matches
     * @throws UnauthorizedActionException if the token is missing, or names an admin no longer on record
     * @throws com.ticketing.system.shared.exception.InvalidTokenException for a malformed, revoked or
     *         member's token
     * @throws com.ticketing.system.shared.exception.SessionExpiredException for an expired token
     * @throws IllegalArgumentException if both dates are given and {@code fromDate} is after
     *         {@code toDate}
     */
    @Transactional(readOnly = true)
    public List<PurchaseHistoryDTO> viewGlobalHistory(String token, GlobalHistoryFiltersDTO filters) {
        requireSystemAdmin(token);
        // Logged only after the check: the filters can name a buyer.
        log.info("Admin request to view global purchase history with filters: {}", filters);

        GlobalHistoryFiltersDTO effectiveFilters = normalizeGlobalHistoryFilters(filters);
        Set<Integer> selectedEventIds = selectedEventIdsOrNull(effectiveFilters);

        OrderReceiptMapper mapper = new OrderReceiptMapper();

        List<PurchaseHistoryDTO.PurchaseRecordDTO> records = orderReceiptRepository.findGlobal(effectiveFilters)
                .stream()
                .map(receipt -> {
                    if (selectedEventIds == null) {
                        return mapper.toPurchaseRecordDTO(
                                receipt, ticketRepository, eventDisplayPort, companyRepository, userRepository);
                    }

                    return mapper.toAdminFilteredPurchaseRecordDTO(
                            receipt, selectedEventIds, ticketRepository, eventDisplayPort, companyRepository, userRepository);
                })
                .filter(record -> !record.tickets().isEmpty())
                .toList();

        log.info("Found {} records for admin global purchase history with filters: {}", records.size(), effectiveFilters);
        return List.of(new PurchaseHistoryDTO(records));
    }




    // *HELPER METHODS* for viewGlobalHistory() that normalize and validate the filters. The admin check
    // and the logging stay in viewGlobalHistory().
    // This method rejects a date range whose start is after its end, and folds the company filter into the
    // event filter, so the query sees only event ids that belong to that company.
    private GlobalHistoryFiltersDTO normalizeGlobalHistoryFilters(GlobalHistoryFiltersDTO filters) {
        GlobalHistoryFiltersDTO f = filters == null
                ? new GlobalHistoryFiltersDTO(null, null, null, null, null)
                : filters;
        if (f.fromDate() != null && f.toDate() != null && f.fromDate().isAfter(f.toDate())) {
            throw new IllegalArgumentException("fromDate must be before or equal to toDate");
        }

        if (f.companyId() == null) {
            return new GlobalHistoryFiltersDTO(
                    f.buyerUserId(),
                    null,
                    f.eventIds(),
                    f.fromDate(),
                    f.toDate());
        }

        List<Integer> companyEventIds = eventRepository.findIdsByCompany(f.companyId());

        List<Integer> effectiveEventIds;

        if (f.eventIds() == null) {
            effectiveEventIds = companyEventIds;
        } else {
            // No exception is thrown; out-of-company eventIds are silently dropped.
            Set<Integer> requestedSet = new HashSet<>(f.eventIds());
            effectiveEventIds = companyEventIds.stream()
                    .filter(requestedSet::contains)
                    .toList();
        }
        return new GlobalHistoryFiltersDTO(
                f.buyerUserId(),
                null,
                effectiveEventIds,
                f.fromDate(),
                f.toDate());
    }


    // *HELPER METHOD* to convert the list of event IDs in the filters to a set for efficient lookup later. Returns null if no event filter is applied.
    private Set<Integer> selectedEventIdsOrNull(GlobalHistoryFiltersDTO filters) {
        if (filters.eventIds() == null) {
            return null;
        }
        return filters.eventIds().stream().collect(Collectors.toSet());
    }





    // *HELPER METHOD* to enforce that the requester is a system admin. Throws if not.
    // Only an admin token passes: a member whose id equals an admin's (both pools start at 1) holds a
    // member token, which validateAdminToken rejects, so the adminRepository lookup never sees it.
    private void requireSystemAdmin(String token) {
        // false only for a missing token; every invalid token throws, and its id cannot be trusted.
        if (!sessionManager.validateAdminToken(token)) {
            log.warn("Unauthorized admin access attempt without a token");
            throw new UnauthorizedActionException("Invalid or non-admin token.");
        }

        int adminId = sessionManager.extractAdminId(token);
        if (adminRepository.findById(adminId) == null) {
            log.warn("Unauthorized access attempt with id: {}", adminId);
            throw new UnauthorizedActionException("Invalid or non-admin token.");
        }
    }

}
