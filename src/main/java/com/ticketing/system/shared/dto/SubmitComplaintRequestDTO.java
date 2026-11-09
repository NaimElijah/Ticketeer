package com.ticketing.system.shared.dto;

/**
 * Input to {@code MessagingService.submitComplaint}. It creates a conversation of type COMPLAINT with
 * ADMIN_GROUP as the counterparty. The service takes the member from the token and does not read
 * {@code memberId}.
 *
 * @param body             the details the member typed
 * @param relatedEntityRef an optional pointer for context, such as {@code "EVENT:123"} or
 *                         {@code "TICKET:abc"}, which the service appends to the stored details
 */
public record SubmitComplaintRequestDTO(
    int memberId,
    String subject,
    String body,
    String relatedEntityRef
) {}
