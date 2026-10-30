/**
 * Offender and allowed-case fixtures for the self-tests in {@code HexagonalRulesTest}. The module
 * graph and domain-containment rules key on package names, so each fixture sits under this root in
 * a package named after the production module it stands in for ({@code fixture.notifications},
 * {@code fixture.sales.domain}, ...). They are test classes, so neither {@code HexagonalRulesTest}'s
 * production importer nor {@code ModularityTests} sees them.
 *
 * <p>They are on the test classpath of every Spring test, though, and some reuse production simple
 * names ({@code AuthenticationService}, {@code NotificationDispatchService}). So a fixture must never
 * carry a Spring stereotype ({@code @Component}, {@code @Service}, ...), {@code @Entity} or
 * {@code @Converter}: component or entity scanning would pick it up, clash with the production bean or
 * mapping, and change the application under test.
 */
package com.ticketing.system.architecture.fixture;
