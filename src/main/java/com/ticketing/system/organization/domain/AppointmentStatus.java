package com.ticketing.system.organization.domain;

// Lifecycle states for a CompanyAppointment (UC-23 / UC-24 design walkthrough).
//   PENDING   - appointment created (UC-23 appoint Owner / UC-24 appoint Manager), awaiting acceptance
//   ACTIVE    - target accepted, role active (a company's founding appointment is created ACTIVE)
//   REJECTED  - target rejected, terminal
//   REVOKED   - an active appointment that was ended, terminal: a Manager appointment by its inviter, an
//               Owner appointment by its inviter or its target (II.4.9). A Manager appointment is also
//               revoked when its holder accepts an Owner offer from the same inviter.
public enum AppointmentStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    REVOKED
}
