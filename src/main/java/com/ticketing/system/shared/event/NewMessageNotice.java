package com.ticketing.system.shared.event;

/**
 * Integration event published by the messaging context (MessagingService) when a new message or
 * inquiry arrives for a recipient: a member or a company owner, never an admin (see
 * MessagingService). The notifications context listens for it and raises a direct-message
 * notification. The listener hands its components to {@code INotificationService.notifyNewMessage}:
 * {@code conversationId} lets the UI deep-link to the thread; {@code senderLabel} is a
 * human-readable origin.
 *
 * <p>An admin outreach does not use it: the whole send publishes one {@link OutreachSentNotice}
 * (ADR-019).
 *
 * @param recipientUserId the user who should receive the notification
 * @param conversationId conversation the message belongs to (for UI deep-linking)
 * @param senderLabel human-readable origin, e.g. "a system admin"
 * @param subject the conversation subject
 * @param snippet a short preview of the message body
 */
public record NewMessageNotice(
        int recipientUserId,
        String conversationId,
        String senderLabel,
        String subject,
        String snippet
) {
}
