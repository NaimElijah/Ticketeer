/**
 * Offender and allowed-case fixtures for the self-tests in {@code HexagonalRulesTest}. The module
 * graph and domain-containment rules key on package names, so each fixture sits under this root in
 * a package named after the production module it stands in for ({@code fixture.notifications},
 * {@code fixture.sales.domain}, ...). They are test classes, so neither {@code HexagonalRulesTest}'s
 * production importer nor {@code ModularityTests} sees them.
 */
package com.ticketing.system.architecture.fixture;
