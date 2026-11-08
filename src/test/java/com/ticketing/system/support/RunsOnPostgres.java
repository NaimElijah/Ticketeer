package com.ticketing.system.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class that must run on PostgreSQL, the production database (ADR-006): before each of its
 * tests, the Spring context's {@code DataSource} must report PostgreSQL, or the test fails (#189).
 *
 * <p>{@code application-test.yml} points every {@code test} context at H2, so an IT whose container stops
 * reaching the context (a lost {@code @ServiceConnection}, or a Boot upgrade that changes what
 * {@code @AutoConfigureTestDatabase} replaces) still boots, on H2, and passes under a PostgreSQL name.
 * Every {@code *IT} class carries this annotation; {@code TestConventionsTest} fails the build on one that
 * does not.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(PostgresDatabaseCheck.class)
public @interface RunsOnPostgres {
}
