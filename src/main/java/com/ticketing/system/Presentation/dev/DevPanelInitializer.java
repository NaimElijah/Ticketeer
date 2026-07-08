package com.ticketing.system.Presentation.dev;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.Core.Application.services.ReservationService;
import com.ticketing.system.Presentation.security.SignOutFlow;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Attaches the {@link DevPanel} floating trigger pill to every UI on
 * init. Spring discovers this bean and registers it during Vaadin's
 * service init phase, the same way {@code AuthBootstrap} attaches its
 * navigation guard.
 *
 * <p>Also bridges the Spring-managed {@link AuthenticationService} and
 * {@link SignOutFlow} into {@link DevPanel}'s static surface so the
 * persona toggles can drive the real login/logout flows instead of the
 * earlier flag-toggle mocks.
 *
 * <p>Removing this file (or its {@code @Component}) hides the dev
 * widget without touching any view code.
 */
@Component
@org.springframework.context.annotation.Profile("dev")
public class DevPanelInitializer implements VaadinServiceInitListener {

    public DevPanelInitializer(ReservationService reservationService,
                               AuthenticationService authenticationService,
                               SignOutFlow signOutFlow,
                               @Value("${platform.admin.username:admin}") String adminUsername,
                               @Value("${platform.admin.password:admin}") String adminPassword) {
        DevPanel.init(reservationService);
        DevPanel.bindBeans(authenticationService, signOutFlow, adminUsername, adminPassword);
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiInit ->
            uiInit.getUI().add(DevPanel.trigger())
        );
    }
}
