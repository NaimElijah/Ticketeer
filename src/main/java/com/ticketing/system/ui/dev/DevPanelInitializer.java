package com.ticketing.system.ui.dev;

import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.SessionIdentity;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.stereotype.Component;

/**
 * Attaches the {@link DevPanel} floating trigger pill to every UI on
 * init. Spring discovers this bean and registers it during Vaadin's
 * service init phase, the same way {@code AuthBootstrap} attaches its
 * navigation guard.
 *
 * <p>Also bridges the Spring-managed {@link AuthenticationService},
 * {@link SignOutFlow} and {@link SessionIdentity} into {@link DevPanel}'s static surface so the
 * persona toggles can drive the real login/logout flows instead of the
 * earlier flag-toggle mocks.
 *
 * <p>Removing this file (or its {@code @Component}) hides the dev
 * widget without touching any view code.
 */
@Component
@org.springframework.context.annotation.Profile("dev")
public class DevPanelInitializer implements VaadinServiceInitListener {

    /** Hands the services to {@link DevPanel}; Spring supplies each parameter. */
    public DevPanelInitializer(ReservationService reservationService,
                               AuthenticationService authenticationService,
                               SignOutFlow signOutFlow,
                               SessionIdentity sessionIdentity,
                               PlatformAdminProperties platformAdmin) {
        DevPanel.init(reservationService);
        DevPanel.bindBeans(authenticationService, signOutFlow, sessionIdentity, platformAdmin.username(),
                platformAdmin.password());
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiInit ->
            uiInit.getUI().add(DevPanel.trigger())
        );
    }
}
