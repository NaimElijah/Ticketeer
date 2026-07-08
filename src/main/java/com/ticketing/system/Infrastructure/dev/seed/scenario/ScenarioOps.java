package com.ticketing.system.Infrastructure.dev.seed.scenario;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.shared.dto.OutreachRequestDTO;
import com.ticketing.system.shared.dto.AppointmentResponseDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.shared.dto.OwnerAppointmentRequestDTO;
import com.ticketing.system.organization.application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.SeatDTO;
import com.ticketing.system.shared.dto.StartConversationRequestDTO;
import com.ticketing.system.shared.dto.SubmitComplaintRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.SeatConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.Infrastructure.dev.seed.DemoClock;
import com.ticketing.system.Infrastructure.dev.seed.SeedHarness;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The scenario operation vocabulary: one handler per command, each dispatching
 * to a real application service and updating the {@link ScenarioContext}. The
 * runner calls {@link #execute} per parsed line; this class owns the
 * {@link SeedHarness} interaction so each line is classified PASS / SKIPPED /
 * FAIL (and {@code expect-error} flips a throw into a PASS).
 *
 * <p>
 * Plain class (not a bean) — instantiated per run by {@code ScenarioRunner}
 * with the services and a fresh {@link DemoClock}.
 */
public final class ScenarioOps {

    @FunctionalInterface
    interface OpHandler {
        void run(ScenarioCommand cmd, ScenarioContext ctx) throws Exception;
    }

    private final AuthenticationService auth;
    private final CompanyManagementService companyService;
    private final EventManagementService eventService;
    private final ReservationService reservationService;
    private final CheckoutService checkoutService;
    private final CatalogService catalogService;
    private final MessagingService messaging;
    private final UserRepository userRepository;
    private final DemoClock clock;

    private final Map<String, OpHandler> registry = new LinkedHashMap<>();
    private int seq = 0;

    public ScenarioOps(AuthenticationService auth,
            CompanyManagementService companyService,
            EventManagementService eventService,
            ReservationService reservationService,
            CheckoutService checkoutService,
            CatalogService catalogService,
            MessagingService messaging,
            UserRepository userRepository,
            DemoClock clock) {
        this.auth = auth;
        this.companyService = companyService;
        this.eventService = eventService;
        this.reservationService = reservationService;
        this.checkoutService = checkoutService;
        this.catalogService = catalogService;
        this.messaging = messaging;
        this.userRepository = userRepository;
        this.clock = clock;
        buildRegistry();
    }

    private void buildRegistry() {
        registry.put("register", this::register);
        registry.put("login", this::login);
        registry.put("login-admin", this::loginAdmin);
        registry.put("logout", this::logout);
        registry.put("logout-all", this::logoutAll);
        registry.put("guest", this::guest);
        registry.put("open-company", this::openCompany);
        registry.put("appoint-owner", this::appointOwner);
        registry.put("appoint-manager", this::appointManager);
        registry.put("confirm", this::confirm);
        registry.put("add-event", this::addEvent);
        registry.put("publish", this::publish);
        registry.put("reserve", this::reserve);
        registry.put("checkout", this::checkout);
        registry.put("cancel-event", this::cancelEvent);
        registry.put("contact-company", this::contactCompany);
        registry.put("submit-complaint", this::submitComplaint);
        registry.put("announce", this::announce);
        registry.put("assert-status", this::assertStatus);
        registry.put("add-coupon", this::addCoupon);
    }

    /** Dispatch one command, recording its outcome through the harness. */
    public void execute(ScenarioCommand cmd, ScenarioContext ctx, SeedHarness harness) {
        if (cmd.op().equals("expect-error")) {
            executeExpectError(cmd, ctx, harness);
            return;
        }
        OpHandler handler = registry.get(cmd.op());
        String label = label(cmd);
        if (handler == null) {
            harness.step("scenario", label, () -> {
                throw new IllegalArgumentException("unknown operation '" + cmd.op() + "' at line " + cmd.line());
            });
            return;
        }
        harness.step("scenario", label, () -> handler.run(cmd, ctx));
    }

    /**
     * {@code expect-error <op> <args…>} — the wrapped op must throw; if it doesn't,
     * that's a FAIL.
     */
    private void executeExpectError(ScenarioCommand cmd, ScenarioContext ctx, SeedHarness harness) {
        String innerOp = cmd.requirePos(0, "operation to expect-error");
        OpHandler handler = registry.get(innerOp);
        String label = "expect-error " + innerOp;
        if (handler == null) {
            harness.step("scenario", label, () -> {
                throw new IllegalArgumentException("unknown operation '" + innerOp + "' at line " + cmd.line());
            });
            return;
        }
        List<String> innerPos = new ArrayList<>(cmd.positional().subList(1, cmd.positional().size()));
        ScenarioCommand inner = new ScenarioCommand(cmd.line(), innerOp, innerPos, cmd.named());
        harness.expectFailure("scenario", label, RuntimeException.class, () -> handler.run(inner, ctx));
    }

    // -- identity --------------------------------------------------------

    private void register(ScenarioCommand cmd, ScenarioContext ctx) {
        String alias = cmd.requirePos(0, "user alias");
        String password = cmd.requirePos(1, "password");
        String email = cmd.requirePos(2, "email");
        int age = Integer.parseInt(cmd.requirePos(3, "age"));
        String username = cmd.named("username", alias);
        GuestSessionDTO guest = auth.startGuestSession();
        auth.register(new RegisterRequestDTO(username, email, password, guest.sessionId(), age));
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("registered user vanished: " + username));
        ctx.registerPrincipal(alias, username, password, user.getUserId());
    }

    private void login(ScenarioCommand cmd, ScenarioContext ctx) {
        String alias = cmd.requirePos(0, "user alias");
        ScenarioContext.Principal p = ctx.principal(alias);
        GuestSessionDTO guest = auth.startGuestSession();
        LoginDTO out = auth.login(new LoginRequestDTO(p.username, p.password, guest.sessionId()));
        p.token = out.authToken().token();
    }

    private void loginAdmin(ScenarioCommand cmd, ScenarioContext ctx) {
        String alias = cmd.requirePos(0, "admin alias");
        String username = cmd.requirePos(1, "admin username");
        String password = cmd.requirePos(2, "admin password");
        AuthTokenDTO tok = auth.signInAsAdmin(username, password);
        ScenarioContext.Principal p = ctx.registerPrincipal(alias, username, password, tok.userId());
        p.token = tok.token();
        p.admin = true;
    }

    private void logout(ScenarioCommand cmd, ScenarioContext ctx) {
        ScenarioContext.Principal p = ctx.principal(cmd.requirePos(0, "user alias"));
        if (p.token != null) {
            auth.logout(new LogoutRequestDTO(p.token));
            p.token = null;
        }
    }

    private void logoutAll(ScenarioCommand cmd, ScenarioContext ctx) {
        for (ScenarioContext.Principal p : ctx.loggedIn()) {
            auth.logout(new LogoutRequestDTO(p.token));
            p.token = null;
        }
    }

    private void guest(ScenarioCommand cmd, ScenarioContext ctx) {
        String alias = cmd.requirePos(0, "guest alias");
        ctx.putGuest(alias, auth.startGuestSession().sessionId());
    }

    // -- company ---------------------------------------------------------

    private void openCompany(ScenarioCommand cmd, ScenarioContext ctx) {
        String owner = cmd.requirePos(0, "owner alias");
        String companyAlias = cmd.requirePos(1, "company alias");
        String name = cmd.requirePos(2, "company name");
        String desc = cmd.pos(3) != null ? cmd.pos(3) : name + " — seeded production company";
        ProductionCompanyDTO c = companyService.registerCompany(ctx.token(owner),
                new CompanyRegistrationDTO(name, desc));
        ctx.putCompany(companyAlias, c.companyId());
    }

    private void appointOwner(ScenarioCommand cmd, ScenarioContext ctx) {
        String by = cmd.requirePos(0, "appointer alias");
        String company = cmd.requirePos(1, "company alias");
        String target = cmd.requirePos(2, "target alias");
        companyService.appointOwner(ctx.token(by),
                new OwnerAppointmentRequestDTO(ctx.companyId(company), ctx.userId(target)));
    }

    private void appointManager(ScenarioCommand cmd, ScenarioContext ctx) {
        String by = cmd.requirePos(0, "appointer alias");
        String company = cmd.requirePos(1, "company alias");
        String target = cmd.requirePos(2, "target alias");
        companyService.appointManager(ctx.token(by),
                new ManagerAppointmentRequestDTO(ctx.companyId(company), ctx.userId(target),
                        parsePerms(cmd.named("perms", ""))));
    }

    private void confirm(ScenarioCommand cmd, ScenarioContext ctx) {
        String alias = cmd.requirePos(0, "invitee alias");
        String company = cmd.requirePos(1, "company alias");
        companyService.respondToAppointment(ctx.token(alias),
                new AppointmentResponseDTO(ctx.companyId(company), true));
    }

    // -- events ----------------------------------------------------------

    private void addEvent(ScenarioCommand cmd, ScenarioContext ctx) {
        String by = cmd.requirePos(0, "manager/owner alias");
        String company = cmd.requirePos(1, "company alias");
        String eventAlias = cmd.requirePos(2, "event alias");
        List<ZoneConfigDTO> zones = new ArrayList<>();
        for (int i = 3; i < cmd.positional().size(); i++) {
            zones.add(parseZone(cmd.positional().get(i), zones.size() + 1));
        }
        if (zones.isEmpty()) {
            throw new IllegalArgumentException(
                    "line " + cmd.line() + ": add-event needs at least one zone spec (e.g. standing:30@50)");
        }
        String token = ctx.token(by);
        int companyId = ctx.companyId(company);
        String name = cmd.named("name", eventAlias);
        EventCategory category = EventCategory.valueOf(cmd.named("category", "CONCERT").toUpperCase());
        int days = cmd.intNamed("days", 30);
        String city = cmd.named("city", "Tel Aviv");
        LocalDateTime start = LocalDateTime.ofInstant(clock.plusDays(days), ZoneId.systemDefault());
        ShowDate show = new ShowDate(start, start.plusHours(3));

        EventDetailDTO created = eventService.addEvent(token, new EventCreationDTO(
                companyId, name, name + " — seeded event", List.of(headliner(name)), category,
                cmd.doubleNamed("rating"), new Location("Israel", city), List.of(show), nonePolicy()));
        int eventId = Integer.parseInt(created.eventId());
        eventService.configureVenueMap(token, companyId, buildVenueMap(created.eventId(), city + " venue", zones));
        if (cmd.boolNamed("publish", false)) {
            eventService.publishEvent(token, companyId, eventId);
        }
        ctx.putEvent(eventAlias, eventId);
    }

    private void publish(ScenarioCommand cmd, ScenarioContext ctx) {
        String by = cmd.requirePos(0, "owner/manager alias");
        String company = cmd.requirePos(1, "company alias");
        String eventAlias = cmd.requirePos(2, "event alias");
        eventService.publishEvent(ctx.token(by), ctx.companyId(company), ctx.eventId(eventAlias));
    }

    // -- buying ----------------------------------------------------------

    private void reserve(ScenarioCommand cmd, ScenarioContext ctx) {
        String buyer = cmd.requirePos(0, "buyer alias");
        String eventAlias = cmd.requirePos(1, "event alias");
        String zoneRef = cmd.requirePos(2, "zone (index, 'standing' or 'seated')");
        int eventId = ctx.eventId(eventAlias);
        String credential = ctx.credential(buyer);
        InventoryZoneDTO zone = resolveZone(credential, eventId, zoneRef);

        InventorySelectionDTO selection;
        if ("SEATED".equals(zone.getZoneType())) {
            List<String> seats = cmd.named("seats") != null
                    ? List.of(cmd.named("seats").split(","))
                    : pickAvailableSeats(zone, cmd.intNamed("qty", 1));
            selection = InventorySelectionDTO.seated(seats);
        } else {
            selection = InventorySelectionDTO.standing(cmd.intNamed("qty", 1));
        }
        if (ctx.isGuest(buyer)) {
            reservationService.reserveForGuest(credential, eventId, zone.getId(), selection);
        } else {
            reservationService.reserveForMember(credential, eventId, zone.getId(), selection);
        }
    }

    private void checkout(ScenarioCommand cmd, ScenarioContext ctx) {
        String buyer = cmd.requirePos(0, "buyer alias");
        String idem = "scenario-" + (++seq);
        CardDetailsDTO card = new CardDetailsDTO("4111111111111111", "123", 12, 2030, "Demo " + buyer);
        if (ctx.isGuest(buyer)) {
            checkoutService.checkoutGuest(ctx.guestSession(buyer),
                    cmd.named("email", buyer + "@guest.demo.test"), idem, "ILS", card,
                    cmd.intNamed("age", 30));
        } else {
            checkoutService.checkoutMember(ctx.token(buyer), idem, "ILS", card);
        }
    }

    private void cancelEvent(ScenarioCommand cmd, ScenarioContext ctx) {
        String by = cmd.requirePos(0, "owner alias");
        String eventAlias = cmd.requirePos(1, "event alias");
        eventService.cancelEventAndRefund(ctx.token(by), ctx.eventId(eventAlias));
    }

    // -- messaging (via #334) --------------------------------------------

    private void contactCompany(ScenarioCommand cmd, ScenarioContext ctx) {
        String from = cmd.requirePos(0, "member alias");
        String company = cmd.requirePos(1, "company alias");
        messaging.startConversation(ctx.token(from), new StartConversationRequestDTO(
                ctx.companyId(company),
                cmd.named("subject", "Inquiry"), cmd.named("body", "Hello, I have a question.")));
    }

    private void submitComplaint(ScenarioCommand cmd, ScenarioContext ctx) {
        String from = cmd.requirePos(0, "member alias");
        messaging.submitComplaint(ctx.token(from), new SubmitComplaintRequestDTO(
                ctx.userId(from), cmd.named("subject", "Complaint"),
                cmd.named("body", "I have an issue that needs attention."), null));
    }

    // Admin proactive outreach (II.6.3.2). 'audience' is kept for scenario back-compat:
    // anything containing "PRODUCER" targets all producers; otherwise all members.
    private void announce(ScenarioCommand cmd, ScenarioContext ctx) {
        ScenarioContext.Principal admin = ctx.principal(cmd.requirePos(0, "admin alias"));
        if (admin.token == null) {
            throw new IllegalStateException("announce needs an admin logged in via 'login-admin'");
        }
        boolean allProducers = cmd.named("audience", "BROADCAST_MEMBERS").toUpperCase().contains("PRODUCER");
        messaging.sendOutreach(admin.token, new OutreachRequestDTO(
                cmd.named("title", "Message"),
                cmd.named("body", "Platform message."),
                List.of(), !allProducers, allProducers));
    }

    // -- assertions / skips ----------------------------------------------

    private void assertStatus(ScenarioCommand cmd, ScenarioContext ctx) {
        String eventAlias = cmd.requirePos(0, "event alias");
        EventStatus expected = EventStatus.valueOf(cmd.requirePos(1, "expected status").toUpperCase());
        String by = cmd.named("by");
        if (by == null) {
            throw new IllegalArgumentException(
                    "line " + cmd.line() + ": assert-status needs by=<owner alias> to read the event");
        }
        EventStatus actual = eventService.getEventDetail(ctx.token(by), ctx.eventId(eventAlias)).status();
        if (actual != expected) {
            throw new IllegalStateException(
                    "expected event " + eventAlias + " to be " + expected + " but it is " + actual);
        }
    }

    private void addCoupon(ScenarioCommand cmd, ScenarioContext ctx) {
        // Coupons are out of scope for this system — recognised so a scenario stays
        // valid,
        // but recorded as SKIPPED (the harness classifies UnsupportedOperationException
        // that way).
        throw new UnsupportedOperationException("coupons are out of scope — no discount/coupon feature in this system");
    }

    // -- helpers ---------------------------------------------------------

    private InventoryZoneDTO resolveZone(String credential, int eventId, String zoneRef) {
        VenueMapDTO map = catalogService.getEventVenueMap(credential, eventId);
        if (map == null) {
            throw new IllegalStateException("no venue map for event " + eventId);
        }
        Integer index = tryParseInt(zoneRef);
        if (index != null) {
            return map.inventoryZones().stream()
                    .filter(z -> z.getId() == index)
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalStateException("no zone with id " + zoneRef + " on event " + eventId));
        }
        String type = zoneRef.equalsIgnoreCase("seated") ? "SEATED" : "STANDING";
        return map.inventoryZones().stream()
                .filter(z -> type.equals(z.getZoneType()) && z.getAvailableAmount() > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no available " + type + " zone on event " + eventId));
    }

    private static List<String> pickAvailableSeats(InventoryZoneDTO zone, int n) {
        List<String> labels = new ArrayList<>(n);
        if (zone.getSeats() != null) {
            for (SeatDTO s : zone.getSeats()) {
                if ("AVAILABLE".equals(s.status())) {
                    labels.add(s.label());
                    if (labels.size() == n) {
                        break;
                    }
                }
            }
        }
        if (labels.size() < n) {
            throw new IllegalStateException(
                    "not enough available seats in zone " + zone.getName() + " (" + labels.size() + "/" + n + ")");
        }
        return labels;
    }

    /**
     * Parse a zone spec like {@code standing:30@50} or {@code seated:10x10@100}.
     */
    private static ZoneConfigDTO parseZone(String spec, int index) {
        String[] atParts = spec.split("@");
        if (atParts.length != 2) {
            throw new IllegalArgumentException("bad zone spec '" + spec + "' (expected type:size@price)");
        }
        double price = Double.parseDouble(atParts[1]);
        String[] typeSize = atParts[0].split(":");
        if (typeSize.length != 2) {
            throw new IllegalArgumentException("bad zone spec '" + spec + "' (expected type:size@price)");
        }
        String type = typeSize[0].toLowerCase();
        String size = typeSize[1];
        if (type.equals("standing")) {
            return new ZoneConfigDTO("Standing " + index, false, Integer.parseInt(size), null, price, null);
        }
        if (type.equals("seated")) {
            String[] rc = size.toLowerCase().split("x");
            if (rc.length != 2) {
                throw new IllegalArgumentException("bad seated spec '" + spec + "' (expected seated:RxC@price)");
            }
            int rows = Integer.parseInt(rc[0]);
            int cols = Integer.parseInt(rc[1]);
            return new ZoneConfigDTO("Seated " + index, true, null, buildSeats(rows, cols), price, null);
        }
        throw new IllegalArgumentException(
                "unknown zone type '" + type + "' in '" + spec + "' (use standing or seated)");
    }

    private static List<SeatConfigDTO> buildSeats(int rows, int cols) {
        List<SeatConfigDTO> seats = new ArrayList<>(rows * cols);
        for (int r = 0; r < rows; r++) {
            char rowLetter = (char) ('A' + r);
            for (int c = 0; c < cols; c++) {
                // Canonical <row><num> labels (A1, no dash) — seat rows are parsed by their leading
                // non-digit run (EventManagementService.getEventZones / SeatPickerPresenter.rowOf),
                // so "A-1" would mis-parse its row as "A-". Matches generateSeats()/buildSeatRow.
                seats.add(new SeatConfigDTO(String.valueOf(rowLetter) + (c + 1), c * 60.0, r * 60.0));
            }
        }
        return seats;
    }

    /**
     * Pack zones into a 2-column grid (full-width final odd zone) so placement
     * always validates.
     */
    private static VenueMapConfigDTO buildVenueMap(String eventId, String venueName, List<ZoneConfigDTO> raw) {
        int n = raw.size();
        int cols = n == 1 ? 1 : 2;
        int rows = (n + 1) / 2;
        List<ZoneConfigDTO> placed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ZoneConfigDTO z = raw.get(i);
            GridPlacementDTO placement = (n % 2 == 1 && i == n - 1)
                    ? new GridPlacementDTO(rows, 1, 1, cols)
                    : new GridPlacementDTO(i / 2 + 1, i % 2 + 1, 1, 1);
            placed.add(new ZoneConfigDTO(z.zoneName(), z.seated(), z.capacity(), z.seats(), z.pricePerTicket(),
                    placement));
        }
        return new VenueMapConfigDTO(eventId, venueName, rows, cols, placed);
    }

    private static List<Permission> parsePerms(String csv) {
        List<Permission> perms = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return perms;
        }
        for (String p : csv.split(",")) {
            if (!p.isBlank()) {
                perms.add(Permission.valueOf(p.trim().toUpperCase()));
            }
        }
        return perms;
    }

    private static String headliner(String name) {
        int dash = name.indexOf('—');
        String head = dash > 0 ? name.substring(0, dash).trim() : name.trim();
        return head.isEmpty() ? name : head;
    }

    private static PurchasePolicyDTO nonePolicy() {
        return new PurchasePolicyDTO("NONE", null, null, null, null);
    }

    private static Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Short, stable label for harness output: line number, op, and up to 3 args. */
    private static String label(ScenarioCommand cmd) {
        int take = Math.min(3, cmd.positional().size());
        String args = String.join(" ", cmd.positional().subList(0, take));
        return ("L" + cmd.line() + " " + cmd.op() + (args.isBlank() ? "" : " " + args)).trim();
    }
}
