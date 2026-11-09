package com.ticketing.system.shared.dto;

// Input to MessagingService.submitComplaint().
// Creates a Conversation with type=COMPLAINT and counterparty=ADMIN_GROUP. The service takes the
// member from the token and does not read 'memberId'.
// 'relatedEntityRef' is an optional pointer (e.g. "EVENT:123" / "TICKET:abc")
// for context — the dispatcher / admin views resolve and display it.
public record SubmitComplaintRequestDTO(
    int memberId,
    String subject,
    String body,
    String relatedEntityRef
) {}
