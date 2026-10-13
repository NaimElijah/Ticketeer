package com.ticketing.system.ui.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * View-level capability gate. {@link AuthBootstrap} reads this
 * annotation and forwards users who lack the named capability to a
 * fallback view, before the target's constructor runs.
 *
 * <p>Use this for views whose entire reason for existing is a single
 * capability — for example a founder-only "Dissolve company" screen
 * gated by {@link Capability#DISSOLVE_COMPANY}. For finer-grained gating
 * (hide a section, hide a button) call {@link Capabilities#has} inside
 * the view body instead.
 *
 * <p>Evaluated by {@link AuthBootstrap} after the signed-in check in
 * {@code AuthBootstrap.guard}.
 *
 * <pre>{@code
 * @Route(value = "owner/dissolve", layout = WorkspaceLayout.class)
 * @PermitAll
 * @RequireCapability(Capability.DISSOLVE_COMPANY)
 * public class CompanyDissolutionView extends LkPage
 * { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequireCapability {
    Capability value();
}
