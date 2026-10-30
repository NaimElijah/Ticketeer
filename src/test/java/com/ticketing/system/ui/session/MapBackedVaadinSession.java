package com.ticketing.system.ui.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import com.vaadin.flow.server.VaadinSession;

/**
 * Test helper: installs a mocked current {@link VaadinSession} whose string-keyed attributes live in a
 * map, so a test arranges and reads session state through the session holders ({@link AuthSession},
 * {@link GuestSession}, ...) instead of copying their private attribute keys.
 */
public final class MapBackedVaadinSession {

    // Vaadin's CurrentInstance holds the current session through a weak reference, so without this the
    // mock could be collected between arranging a test and running it (MapBackedVaadinSessionTest).
    private static final ThreadLocal<VaadinSession> INSTALLED = new ThreadLocal<>();

    private MapBackedVaadinSession() {
    }

    /** Makes a fresh map-backed session the current one; pair with {@link #uninstall()}. */
    public static void install() {
        Map<String, Object> attributes = new HashMap<>();
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0, String.class)));
        doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(anyString(), any());
        INSTALLED.set(session);
        VaadinSession.setCurrent(session);
    }

    /** Clears the current session, so no state leaks into the next test. */
    public static void uninstall() {
        VaadinSession.setCurrent(null);
        INSTALLED.remove();
    }
}
