/**
 * UI &mdash; the single Vaadin driving (inbound) adapter for the whole platform.
 *
 * <p>Holds all Vaadin routes/views, their presenters, the shared shell (layouts, the
 * {@code AuthBootstrap} navigation guard, capabilities, session helpers, component kit), and the dev
 * panel. As the one inbound adapter it is allowed to depend on every bounded context's inbound
 * application API; no backend context may depend on {@code ui} (enforced at Step 10). Keeping the UI
 * as a single module &mdash; rather than sharding views per context &mdash; is a deliberate decision
 * that avoids the layout&harr;view and security&harr;view dependency cycles in the Vaadin layer while
 * remaining canonical hexagonal (the UI is one driving adapter).
 *
 * <p>The Vaadin route/component scan is pinned to this package via {@code vaadin.allowed-packages} in
 * {@code application.yml}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "UI (Vaadin driving adapter)", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.ui;
