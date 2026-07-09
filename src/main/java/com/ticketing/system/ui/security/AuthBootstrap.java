package com.ticketing.system.ui.security;

import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.company.CompanyRegistrationView;
import com.ticketing.system.ui.views.company.MyCompaniesView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Global navigation guard for the V2 placeholder UI.
 *
 * <p>Two-step check on every navigation:
 * <ol>
 *   <li><b>Auth gate</b> — targets not annotated {@link AnonymousAllowed}
 *       require a signed-in user. Signed-out users are forwarded to
 *       the unified {@link LoginView}, which decides admin vs member by
 *       username after auth.</li>
 *   <li><b>Capability gate</b> — targets annotated
 *       {@link RequireCapability} require the user to hold the named
 *       capability. The {@code fallbackFor} table picks the right
 *       "you can't go here, try this instead" destination based on the
 *       missing capability:
 *       <ul>
 *         <li>admin capabilities → {@link LoginView}</li>
 *         <li>owner workspace caps when the user has no company →
 *             {@link CompanyRegistrationView} (so they can become one)</li>
 *         <li>owner workspace caps when the user already has a company
 *             but lacks this specific permission → {@link OwnerDashboardView}</li>
 *         <li>everything else → {@link MyCompaniesView}</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Replaces the earlier marker-interface system. {@link Capability}
 * subsumes the two role boundaries those markers used to enforce.
 */
@Component
public class AuthBootstrap implements VaadinServiceInitListener {

    /** Capabilities that belong to the system-admin pool. */
    private static final Set<Capability> ADMIN_CAPS = EnumSet.of(
        Capability.ADMIN_WORKSPACE,
        Capability.VIEW_GLOBAL_HISTORY,
        Capability.BROADCAST_ANNOUNCEMENT,
        Capability.MANAGE_COMPLAINTS,
        Capability.VIEW_ORG_TREES,
        Capability.ADMIN_SETTINGS
    );

    /** Capabilities that imply company membership ("owner workspace"). */
    private static final Set<Capability> WORKSPACE_CAPS = EnumSet.of(
        Capability.OWNER_WORKSPACE,
        Capability.VIEW_COMPANY_EVENTS,
        Capability.EDIT_COMPANY_EVENTS,
        Capability.VIEW_COMPANY_SALES,
        Capability.EDIT_PURCHASE_POLICIES,
        Capability.RESPOND_INQUIRIES,
        Capability.MANAGE_VENUE_MAPS,
        Capability.APPOINT_MANAGER,
        Capability.APPOINT_CO_OWNER,
        Capability.EDIT_MANAGER_PERMISSIONS,
        Capability.REVOKE_MANAGER,
        Capability.CANCEL_EVENT,
        Capability.DISSOLVE_COMPANY,
        Capability.TRANSFER_FOUNDERSHIP
    );

    /** Session attribute holding a permission-denied message to flash after the redirect lands. */
    private static final String PENDING_DENIAL_KEY = "authBootstrap.pendingDenialToast";

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiInit -> {
            uiInit.getUI().addBeforeEnterListener(this::guard);
            // Flash the deferred permission-denied toast once the forwarded navigation has landed.
            uiInit.getUI().addAfterNavigationListener(e -> flushPendingDenialToast());
        });
    }

    private void guard(BeforeEnterEvent event) {
        Class<?> target = event.getNavigationTarget();
        RequireCapability cap = target.getAnnotation(RequireCapability.class);

        // 1. Auth gate — every non-anonymous view needs a session. The unified
        // LoginView is the single sign-in surface; if the user types an admin
        // username, it auto-routes them to AdminDashboardView after auth.
        if (!target.isAnnotationPresent(AnonymousAllowed.class)) {
            if (!AuthSession.isSignedIn()) {
                event.forwardTo(LoginView.class);
                return;
            }
        }

        // 2. Capability gate.
        if (cap != null && !Capabilities.has(cap.value())) {
            // A signed-in company member who reached a workspace action they lack the permission
            // for — e.g. a manager clicking "Policies" without EDIT_PURCHASE_POLICIES — gets a clear
            // toast instead of a silent redirect. The admin-shell and "no company yet" forwards keep
            // their existing (silent) behavior; those aren't a missing-permission situation.
            //
            // The toast is DEFERRED (stored, then shown by the after-navigation listener): opening a
            // Notification here and immediately forwardTo()-ing reroutes in the same navigation cycle,
            // so the toast wouldn't reliably render on the destination. Flashing it after the redirect
            // lands makes it dependable.
            if (WORKSPACE_CAPS.contains(cap.value()) && Capabilities.has(Capability.OWNER_WORKSPACE)) {
                VaadinSession session = VaadinSession.getCurrent();
                if (session != null) {
                    session.setAttribute(PENDING_DENIAL_KEY,
                        "You don't have that action in your permissions - contact an owner");
                }
            }
            event.forwardTo(fallbackFor(cap.value()));
        }
    }

    /** Shows (once) any permission-denied message queued by {@link #guard} on the previous redirect. */
    private void flushPendingDenialToast() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) {
            return;
        }
        Object pending = session.getAttribute(PENDING_DENIAL_KEY);
        if (pending instanceof String message) {
            session.setAttribute(PENDING_DENIAL_KEY, null);
            Toasts.warn(message);
        }
    }

    /** Where to send a user who lacks the named capability. */
    private static Class<? extends com.vaadin.flow.component.Component> fallbackFor(Capability c) {
        if (ADMIN_CAPS.contains(c)) {
            // Signed-in member trying to reach the admin shell — bounce to
            // the unified sign-in. Re-authentication as an admin will land
            // them on the platform shell.
            return LoginView.class;
        }
        if (WORKSPACE_CAPS.contains(c)) {
            // No company yet → become one. Otherwise the user has a
            // company but lacks this specific permission inside it, so
            // park them on the workspace home where they can see what
            // they CAN do.
            return Capabilities.has(Capability.OWNER_WORKSPACE)
                ? OwnerDashboardView.class
                : CompanyRegistrationView.class;
        }
        return MyCompaniesView.class;
    }
}
