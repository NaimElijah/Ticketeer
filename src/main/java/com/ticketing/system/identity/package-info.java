/**
 * Identity &amp; Access bounded context.
 *
 * <p>Owns authentication and the security principals of the platform: the {@code User} and
 * {@code Admin} aggregates and the {@code Session} lifecycle. Laid out hexagonally:
 * <ul>
 *   <li>{@code domain} &mdash; the {@code User}, {@code Session}, {@code Admin} aggregates;</li>
 *   <li>{@code application.port.out} &mdash; driven ports ({@code UserRepository},
 *       {@code SessionRepository}, {@code AdminRepository}, {@code PasswordHasher},
 *       {@code SessionManager}, {@code CartRestorationPort});</li>
 *   <li>{@code application.service} &mdash; the {@code AuthenticationService} use-case implementation
 *       and the {@code MemberQueryService} read side;</li>
 *   <li>{@code adapter.out.persistence} / {@code adapter.out.security} &mdash; the JPA/in-memory
 *       repository adapters and the BCrypt / JWT security adapters.</li>
 * </ul>
 *
 * <p>Identity has no inbound port: other contexts use its ports and entities directly, among them
 * {@code SessionManager}, {@code UserRepository}, {@code AdminRepository}, {@code PasswordHasher},
 * {@code User} and {@code Admin}. The module is declared {@code Type.OPEN}; the module map in
 * {@code HexagonalRulesTest} is the boundary gate.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity & Access", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.identity;
