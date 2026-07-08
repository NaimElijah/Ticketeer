package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.messaging.application.service.MessagingService;

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
