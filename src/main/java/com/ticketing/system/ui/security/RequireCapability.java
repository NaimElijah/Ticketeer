package com.ticketing.system.ui.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * View-level capability gate. {@link AuthBootstrap} reads this
 * annotation and forwards users who lack the named capability to a
 * fallback view.
 *
 * <p>Use this for views whose entire reason for existing is a single
 * capability — for example the managers screen gated by
 * {@link Capability#APPOINT_MANAGER}. For finer-grained gating
 * (hide a section, hide a button) call {@link Capabilities#has} inside
 * the view body instead.
 *
 * <p>Evaluated by {@link AuthBootstrap} after the signed-in check in
 * {@code AuthBootstrap.guard}.
 *
 * <pre>{@code
 * @Route(value = "owner/managers", layout = WorkspaceLayout.class)
 * @PermitAll
 * @RequireCapability(Capability.APPOINT_MANAGER)
 * public class ManagerListView extends LkPage
 * { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequireCapability {
    /** The capability a user must hold to open the view. */
    Capability value();
}
