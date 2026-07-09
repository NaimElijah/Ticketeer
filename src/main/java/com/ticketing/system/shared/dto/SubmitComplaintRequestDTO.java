package com.ticketing.system.shared.dto;

// Input to MessagingService.submitComplaint() — replaces the old Complaint flow.
// Creates a Conversation with type=COMPLAINT and counterparty=ADMIN_GROUP.
// 'relatedEntityRef' is an optional pointer (e.g. "EVENT:123" / "TICKET:abc")
// for context — the dispatcher / admin views resolve and display it.
public record SubmitComplaintRequestDTO(
    int memberId,
    String subject,
    String body,
    String relatedEntityRef
) {}
