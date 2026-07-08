package com.ticketing.system.organization.domain;

// Lifecycle states for a CompanyAppointment (UC-23 / UC-24 design walkthrough).
//   PENDING   - appointment created (UC-23 appoint Owner / UC-24 appoint Manager), awaiting acceptance
//   ACTIVE    - target accepted, role active
//   REJECTED  - target rejected, terminal
//   REVOKED   - Owner who appointed has revoked (UC-24 only — Owner appointments cannot be revoked in v0 per II.4.9 Cancelled)
public enum AppointmentStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    REVOKED
}
