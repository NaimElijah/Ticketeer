package com.ticketing.system.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.shared.ui.Transport;
import com.vaadin.flow.theme.Theme;

/**
 * Application shell for the TicketHub Vaadin app.
 *
 * <p>The {@code @Theme("tickethub")} annotation activates the custom theme
 * at {@code src/main/frontend/themes/tickethub/} — its {@code styles.css}
 * (ported verbatim from {@code docs/tickethub-ui/TicketHub UI.html})
 * supplies every {@code .lk-*}, {@code .bz-*}, {@code .vk-*}, {@code .pe-*},
 * {@code .md-*}, {@code .ow-*}, {@code .acct-*}, {@code .auth-*},
 * {@code .org-*} class name that the Java component kit (in
 * {@code Presentation/components/kit/}) attaches to its rendered DOM.
 *
 * <p>Long-polling transport per [#212 V2-F-07] — Tier C exempts real-time
 * WebSocket push. Background threads use {@code UI.access(...)} to update
 * the UI safely from scheduler threads.
 */
@Push(transport = Transport.LONG_POLLING)
@Theme("tickethub")
public class AppShell implements AppShellConfigurator {
}
