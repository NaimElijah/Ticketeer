package com.ticketing.system.sales.domain;

// Lifecycle states for a Ticket (unified inventory + post-purchase aggregate).
// State machine:
//   AVAILABLE -> RESERVED          (UC-9 reservation)
//   RESERVED  -> PAID              (UC-10 successful charge)
//   PAID      -> ISSUED            (UC-34 successful issuance)
//   ISSUED    -> USED              (gate scan — no UC in v0)
//   PAID/ISSUED -> REFUNDED        (UC-4 auto-refund)
//   RESERVED  -> AVAILABLE         (UC-2 expiry release)
//   *         -> VOIDED            (admin / ops)
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PAID,
    ISSUED,
    USED,
    REFUNDED,
    VOIDED
}
