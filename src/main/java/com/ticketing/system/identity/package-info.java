/**
 * Identity &amp; Access bounded context.
 *
 * <p>Owns authentication and the security principals of the platform: the {@code User} and
 * {@code Admin} aggregates and the {@code Session} lifecycle. Laid out hexagonally:
 * <ul>
 *   <li>{@code domain} &mdash; the {@code User}, {@code Session}, {@code Admin} aggregates;</li>
 *   <li>{@code application.port.out} &mdash; driven ports ({@code UserRepository},
 *       {@code SessionRepository}, {@code AdminRepository}, {@code PasswordHasher},
 *       {@code SessionManager});</li>
 *   <li>{@code application.service} &mdash; the {@code AuthenticationService} use-case implementation;</li>
 *   <li>{@code adapter.out.persistence} / {@code adapter.out.security} &mdash; the JPA/in-memory
 *       repository adapters and the BCrypt / JWT security adapters.</li>
 * </ul>
 *
 * <p>Migration note: other, not-yet-migrated contexts still reference this context's ports directly;
 * those calls are routed through an inbound port when their owning contexts move, and module-boundary
 * verification is switched on at Step 10.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity & Access", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.identity;
