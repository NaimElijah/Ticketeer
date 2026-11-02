/**
 * Shared kernel for the ticketing platform's bounded contexts.
 *
 * <p>Holds what every other module may depend on, and depends on no other module:
 * <ul>
 *   <li>the {@link com.ticketing.system.shared.exception.DomainException} hierarchy;</li>
 *   <li>the base domain contracts {@link com.ticketing.system.shared.IRepository} and
 *       {@link com.ticketing.system.shared.InvariantChecked};</li>
 *   <li>published-language DTOs ({@code dto}), integration notices ({@code event}) and the
 *       purchase-policy domain ({@code domain.policy}) with the user-text length rule
 *       ({@code domain.TextLimits});</li>
 *   <li>cross-cutting infrastructure: the {@code Clock} and password-encoder beans and the bound
 *       configuration properties ({@code config}), the WSEP HTTP client ({@code external}), the
 *       logging aspect ({@code logging}), the platform-activity metrics ({@code metrics}), and the
 *       persistence support ({@code persistence}): adapter profile names, repository locks, the
 *       optimistic-lock translation aspect and the PostgreSQL driver timeouts.</li>
 * </ul>
 *
 * <p>An <em>open</em> Spring Modulith module ({@code Type.OPEN}), like every module here. Modulith
 * verifies the module model; the dependency direction itself is enforced by the ArchUnit module map
 * in {@code HexagonalRulesTest} (ADR-011).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared Kernel", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.shared;
