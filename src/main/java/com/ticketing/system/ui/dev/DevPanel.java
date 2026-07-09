package com.ticketing.system.ui.dev;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.BuyerContextDTO;
import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CompanyManagementBridge;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MockPermissions;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.textfield.TextField;

import java.util.EnumSet;
import java.util.List;

public final class DevPanel {

    private static final String SECTION_LABEL_COLOR = "#475569";
    private static final String SECTION_SUB_COLOR   = "#94a3b8";
    private static final String DIVIDER_COLOR       = "#e2e8f0";

    private static volatile ReservationService reservationService;

    static AuthenticationService AUTH;
    static SignOutFlow SIGN_OUT_FLOW;

    // The real platform-admin credentials (platform.admin.* — default admin/admin),
    // used by the "Admin" persona toggle to sign in through the dedicated admin path.
    private static volatile String adminUsername = "admin";
    private static volatile String adminPassword = "admin";

    private DevPanel() { }

    public static void init(ReservationService rs) {
        reservationService = rs;
    }

    public static void bindBeans(AuthenticationService auth, SignOutFlow signOutFlow,
                                 String adminUsername, String adminPassword) {
        AUTH = auth;
        SIGN_OUT_FLOW = signOutFlow;
        DevPanel.adminUsername = adminUsername;
        DevPanel.adminPassword = adminPassword;
    }

    private static void abandonCurrentOrder() {
        if (reservationService == null) return;
        try {
            String token = AuthSession.token();
            if (token != null) {
                ActiveOrderDTO order = reservationService.viewMyActiveOrder(token);
                if (order != null && order.userId() != null) {
                    reservationService.abandonActiveOrder(BuyerContextDTO.member(order.userId()));
                }
            }
        } catch (Exception ignored) { }
    }

    private static void signInAs(String username, String password) {
        try {
            if (AuthSession.isSignedIn()) {
                SIGN_OUT_FLOW.execute();
            }
            String guestSid = GuestSession.sessionId();
            if (guestSid == null) {
                guestSid = AUTH.startGuestSession().sessionId();
                GuestSession.setSessionId(guestSid);
            }
            var dto = AUTH.login(new LoginRequestDTO(username, password, guestSid));
            AuthSession.storeAuth(dto.authToken());
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Sign in as the real platform admin via the dedicated admin sign-in, so the
     * session carries a genuine admin token (admin-ness is token-derived). Replaces
     * the retired {@code dev.admin} member account, which is no longer in the admin pool.
     */
    private static void signInAsAdmin() {
        try {
            if (AuthSession.isSignedIn()) {
                SIGN_OUT_FLOW.execute();
            }
            AuthSession.storeAuth(AUTH.signInAsAdmin(adminUsername, adminPassword));
        } catch (RuntimeException ignored) {
        }
    }

    private static List<UserCompanyDTO> listFromService() {
        Integer userId = AuthSession.userId();
        if (userId == null || CompanyManagementBridge.service() == null) return List.of();
        return CompanyManagementBridge.service().listForUser(userId);
    }

    private static UserCompanyDTO currentCompanyFromService() {
        List<UserCompanyDTO> all = listFromService();
        if (all.isEmpty()) return null;
        Integer currentId = CurrentCompanies.currentCompanyId();
        if (currentId != null) {
            for (UserCompanyDTO m : all) {
                if (m.companyId() == currentId) return m;
            }
        }
        return all.get(0);
    }

    public static Button trigger() {
        Icon bug = new Icon(VaadinIcon.BUG);
        bug.getStyle().set("width", "14px").set("height", "14px");
        Button b = new Button("DEV", bug, e -> show());
        b.getElement().setAttribute("aria-label", "Open dev panel");
        b.getStyle()
            .set("position", "fixed")
            .set("bottom", "20px")
            .set("right", "20px")
            .set("z-index", "9999")
            .set("background", "#0f172a")
            .set("color", "#fff")
            .set("border", "1px solid #334155")
            .set("border-radius", "999px")
            .set("padding", "8px 16px")
            .set("font-family", "var(--mono)")
            .set("font-weight", "800")
            .set("font-size", "12px")
            .set("letter-spacing", ".06em")
            .set("box-shadow", "0 6px 20px rgba(15,23,42,0.4)")
            .set("cursor", "pointer");
        return b;
    }

    public static void show() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Dev panel — session override");
        dialog.setWidth("680px");
        dialog.setMaxWidth("96vw");
        dialog.setDraggable(true);

        Div content = new Div();
        content.getStyle().set("display", "flex").set("flex-direction", "column")
            .set("gap", "0").set("max-height", "70vh").set("overflow", "auto")
            .set("padding", "4px 2px");

        content.add(
            section("Persona shortcuts",  "Click to toggle. Member auto-engages with role buttons; Admin stacks with the rest.",
                buildPersonaRow(dialog)),
            section("Identity",           "Switches log in via the real AuthenticationService against the dev-seeded users.",
                buildIdentityRow(dialog)),
            section("Companies",          "Drives OWNER_WORKSPACE + the manager-vs-owner branch.",
                buildCompaniesBlock(dialog)),
            section("Manager permissions", currentManagerSubtitle(),
                buildManagerPermsBlock()),
            section("Capability inspector", "Live from Capabilities.forCurrentUser()",
                buildCapabilityCloud())
        );
        dialog.add(content);

        Button close = new Button("Close", e -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button reload = new Button("Apply & reload", e -> {
            dialog.close();
            UI.getCurrent().getPage().reload();
        });
        reload.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(close, reload);

        dialog.open();
    }

    private static Div section(String label, String subtitle, Component content) {
        Div sect = new Div();
        sect.getStyle()
            .set("display", "flex").set("flex-direction", "column")
            .set("gap", "8px").set("padding", "14px 0")
            .set("border-bottom", "1px solid " + DIVIDER_COLOR);

        Span lbl = new Span(label);
        lbl.getStyle()
            .set("font-size", "11px").set("font-weight", "800")
            .set("letter-spacing", ".08em").set("text-transform", "uppercase")
            .set("color", SECTION_LABEL_COLOR);
        sect.add(lbl);

        if (subtitle != null && !subtitle.isEmpty()) {
            Span sub = new Span(subtitle);
            sub.getStyle()
                .set("font-size", "12.5px")
                .set("color", SECTION_SUB_COLOR)
                .set("margin-top", "-4px").set("line-height", "1.4");
            sect.add(sub);
        }
        sect.add(content);
        return sect;
    }

    private static Div hrow(int gap, Component... children) {
        Div d = new Div();
        d.getStyle().set("display", "flex").set("flex-wrap", "wrap")
            .set("align-items", "center").set("gap", gap + "px");
        d.add(children);
        return d;
    }

    private static Div vcol(int gap, Component... children) {
        Div d = new Div();
        d.getStyle().set("display", "flex").set("flex-direction", "column")
            .set("gap", gap + "px");
        d.add(children);
        return d;
    }

    private static Button pillBtn(String label, String bg, String fg, Runnable onClick) {
        Button b = new Button(label, e -> onClick.run());
        b.getStyle()
            .set("background", bg).set("color", fg)
            .set("border", "1px solid " + DIVIDER_COLOR)
            .set("border-radius", "7px")
            .set("padding", "5px 12px")
            .set("font-size", "12.5px").set("font-weight", "700")
            .set("min-width", "auto");
        return b;
    }

    private static Button ghostBtn(String label, Runnable onClick) {
        return pillBtn(label, "#fff", "#1e293b", onClick);
    }

    private static Button dangerBtn(String label, Runnable onClick) {
        Button b = pillBtn(label, "#fff", "#b91c1c", onClick);
        b.getStyle().set("border-color", "#fca5a5");
        return b;
    }

    private static Component buildPersonaRow(Dialog dialog) {
        return hrow(6,
            personaToggle("Guest",  isGuestSelected(),    () -> togglePersona("Guest",  dialog)),
            personaToggle("Member", isMemberSelected(),   () -> togglePersona("Member", dialog)),
            personaToggle("Admin",  AuthSession.isAdmin(), () -> togglePersona("Admin",  dialog))
        );
    }

    private static Button personaToggle(String label, boolean selected, Runnable onClick) {
        Button b = new Button(label, e -> onClick.run());
        b.getStyle()
            .set("border-radius", "999px")
            .set("padding", "5px 14px")
            .set("font-size", "12.5px")
            .set("font-weight", "700")
            .set("cursor", "pointer")
            .set("min-width", "auto");
        if (selected) {
            b.getStyle()
                .set("background", "#0f172a").set("color", "#fff")
                .set("border", "1px solid #0f172a");
        } else {
            b.getStyle()
                .set("background", "#fff").set("color", "#1e293b")
                .set("border", "1px solid " + DIVIDER_COLOR);
        }
        return b;
    }

    private static boolean isGuestSelected() {
        return !AuthSession.isSignedIn() && !AuthSession.isAdmin();
    }

    private static boolean isMemberSelected() {
        return AuthSession.isSignedIn();
    }

    private static void togglePersona(String name, Dialog dialog) {
        switch (name) {
            case "Guest" -> {
                if (AuthSession.isSignedIn() || AuthSession.isAdmin()) {
                    abandonCurrentOrder();
                    SIGN_OUT_FLOW.execute();
                }
            }
            case "Member" -> {
                if (!AuthSession.isSignedIn()) {
                    signInAs(DevUserSeeder.MEMBER_USERNAME, DevUserSeeder.SHARED_PASSWORD);
                } else if (listFromService().isEmpty() && !AuthSession.isAdmin()) {
                    abandonCurrentOrder();
                    SIGN_OUT_FLOW.execute();
                }
            }
            case "Admin" -> {
                if (AuthSession.isAdmin()) {
                    signInAs(DevUserSeeder.MEMBER_USERNAME, DevUserSeeder.SHARED_PASSWORD);
                } else {
                    signInAsAdmin();
                }
            }
        }
        refresh(dialog);
    }

    private static Component buildIdentityRow(Dialog dialog) {
        TextField name = new TextField();
        name.setLabel("Display name");
        name.setValue(AuthSession.displayName() == null ? "" : AuthSession.displayName());
        name.setWidth("220px");
        name.setReadOnly(true);

        Checkbox signedIn = new Checkbox("Signed in", AuthSession.isSignedIn());
        Checkbox isAdmin  = new Checkbox("Admin pool", AuthSession.isAdmin());

        signedIn.addValueChangeListener(e -> togglePersona("Member", dialog));
        isAdmin.addValueChangeListener(e -> togglePersona("Admin", dialog));

        return hrow(16, name, signedIn, isAdmin);
    }

    private static Component buildCompaniesBlock(Dialog dialog) {
        Div block = vcol(8);

        List<UserCompanyDTO> all = listFromService();
        if (all.isEmpty()) {
            Span hint = new Span("No companies for current user.");
            hint.getStyle().set("color", SECTION_SUB_COLOR).set("font-size", "13px");
            block.add(hint);
        } else {
            Integer currentId = CurrentCompanies.currentCompanyId();
            for (UserCompanyDTO company : all) {
                boolean current = Integer.valueOf(company.companyId()).equals(currentId)
                    || (currentId == null && all.get(0).companyId() == company.companyId());
                Span tag = new Span();
                tag.getStyle().set("flex", "1").set("font-size", "13.5px")
                    .set("min-width", "180px");
                tag.getElement().setProperty("innerHTML",
                    (current ? "<span style='color:#ca8a04'>★</span> " : "")
                    + "<b>" + escape(company.name()) + "</b>"
                    + " <span style='color:" + SECTION_SUB_COLOR + "'>· " + company.role() + "</span>");
                Button select = ghostBtn("Make current", () -> {
                    CurrentCompanies.setCurrentCompany(company.companyId());
                    refresh(dialog);
                });
                block.add(hrow(8, tag, select));
            }
        }

        return block;
    }

    private static String currentManagerSubtitle() {
        UserCompanyDTO current = currentCompanyFromService();
        if (current == null) return "No current company — pick one above.";
        if (!"Manager".equals(current.role())) {
            return "Current role is " + current.role() + " — grants only apply to Managers.";
        }
        return "Current manager permissions (read-only — edit via the real UI).";
    }

    private static Component buildManagerPermsBlock() {
        UserCompanyDTO current = currentCompanyFromService();
        if (current == null || !"Manager".equals(current.role())) {
            return new Span();
        }

        EnumSet<Permission> held = EnumSet.noneOf(Permission.class);
        held.addAll(current.managerPermissions());

        Div grid = new Div();
        grid.getStyle().set("display", "grid")
            .set("grid-template-columns", "repeat(2, minmax(0, 1fr))")
            .set("gap", "4px 16px");

        for (Capability capability : MockPermissions.GRANTABLE) {
            Permission permission = permissionForCapability(capability);
            Checkbox cb = new Checkbox(prettify(capability.name()), held.contains(permission));
            cb.setReadOnly(true);
            grid.add(cb);
        }
        return grid;
    }

    private static Permission permissionForCapability(Capability capability) {
        return switch (capability) {
            case VIEW_COMPANY_SALES -> Permission.VIEW_SALES;
            case EDIT_PURCHASE_POLICIES -> Permission.EDIT_POLICIES;
            case RESPOND_INQUIRIES -> Permission.RESPOND_TO_INQUIRIES;
            case MANAGE_VENUE_MAPS -> Permission.CONFIGURE_VENUE;
            case VIEW_COMPANY_EVENTS, EDIT_COMPANY_EVENTS -> Permission.MANAGE_INVENTORY;
            default -> throw new IllegalArgumentException("Not a manager-grantable capability: " + capability);
        };
    }

    private static Component buildCapabilityCloud() {
        var caps = Capabilities.forCurrentUser();
        Div cloud = new Div();
        cloud.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "6px");

        if (caps.isEmpty()) {
            Span none = new Span("(none)");
            none.getStyle().set("color", SECTION_SUB_COLOR);
            cloud.add(none);
        } else {
            for (Capability c : caps) {
                cloud.add(new LkBadge(c.name(), LkBadge.Tone.primary).small());
            }
        }
        return cloud;
    }

    private static void refresh(Dialog dialog) {
        dialog.close();
        show();
    }

    private static String prettify(String snake) {
        String[] parts = snake.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (i > 0) sb.append(' ');
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
            sb.append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
