package com.ticketing.system.bootstrap.scheduling;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks every live Vaadin UI so background schedulers can iterate connected
 * sessions without holding a Vaadin session lock. UIs self-remove on detach.
 *
 * Follows the same VaadinServiceInitListener pattern as GuestSessionBootstrap.
 */
@Component
public class ActiveUiRegistry implements VaadinServiceInitListener {

    private final Set<UI> activeUIs = ConcurrentHashMap.newKeySet();

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiInit -> {
            UI ui = uiInit.getUI();
            activeUIs.add(ui);
            ui.addDetachListener(e -> activeUIs.remove(ui));
        });
    }

    public Set<UI> getActiveUIs() {
        return Set.copyOf(activeUIs);
    }
}
