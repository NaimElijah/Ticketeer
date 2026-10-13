/**
 * Reporting &amp; Analytics context &mdash; the platform's read/query (CQRS) side.
 *
 * <p>A top-level <b>consumer</b> context: it holds cross-context read-only query services that
 * assemble read models by reading from other bounded contexts (catalog, sales, messaging, identity,
 * organization) and is itself depended on only by the {@code ui} layer. Because it reads downward and
 * is consumed only upward by the UI, it introduces no dependency cycles &mdash; the reason both queries
 * were relocated here out of the foundational identity/organization contexts (which they read upward).
 *
 * <p>Holds {@code MemberAccountService} (UC-16 member purchase-history / receipt reads, formerly in
 * identity) and {@code CompanyAnalyticsService} (owner-workspace dashboard and company sales reads,
 * formerly in organization). Hexagonal layout: {@code application.service} query services that depend
 * on the driven ports and DTOs owned by the contexts they read.
 *
 * <p>Declared {@code OPEN} so its query services stay directly callable by the presentation layer.
 */
// Reporting module: read-only CQRS consumer; OPEN so UI presenters can call its query services directly.
@org.springframework.modulith.ApplicationModule(
        displayName = "Reporting & Analytics",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.reporting;
