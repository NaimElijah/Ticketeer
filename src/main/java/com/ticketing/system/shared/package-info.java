/**
 * Shared kernel for the ticketing platform's bounded contexts.
 *
 * <p>Holds the cross-context domain vocabulary every module is permitted to depend on: the
 * {@link com.ticketing.system.shared.exception.DomainException} hierarchy and the base domain
 * contracts {@link com.ticketing.system.shared.IRepository} and
 * {@link com.ticketing.system.shared.InvariantChecked}. Kept deliberately small.
 *
 * <p>This is the first module extracted in the DDD/hexagonal migration. It becomes an <em>open</em>
 * Spring Modulith module (depended on by everyone, depending on nothing) once boundary verification
 * is switched on at Step 10.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared Kernel")
package com.ticketing.system.shared;
