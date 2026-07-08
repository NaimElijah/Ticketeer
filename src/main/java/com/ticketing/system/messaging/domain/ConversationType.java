package com.ticketing.system.messaging.domain;

// Categorizes a Conversation by its purpose. Drives RBAC and routing.
//   INQUIRY   - Member contacts a Company; two-way chat (II.3.10 / II.4.4)
//   COMPLAINT - Member files a one-shot complaint to the admin queue (II.3.3 / II.6.3.1):
//               exactly one member message + one admin reply, which resolves it
//   DIRECT    - Admin↔Member proactive outreach; two-way chat (II.6.3.2)
public enum ConversationType {
    INQUIRY,
    COMPLAINT,
    DIRECT
}
