package com.ticketing.system.shared.event;

import java.util.List;

/**
 * Integration event published by the messaging context (MessagingService) once per admin outreach
 * send (II.6.3.b), naming every recipient and the conversation opened for them. The notifications
 * context listens for it and raises one direct-message notification per recipient, with the same
 * content as a {@link NewMessageNotice} would carry.
 *
 * <p>One notice per send, not one per recipient: notices wait in the {@code notify-} executor's
 * bounded queue, which drops what does not fit (ADR-004), so a send to more members than the queue
 * holds would lose their alerts and other users' (#187).
 *
 * @param recipients  every recipient of the send, at least one; copied, so later changes to the
 *                    caller's list do not reach the notice
 * @param senderLabel human-readable origin, e.g. "a system admin"
 * @param subject     the conversations' shared subject
 * @param snippet     a short preview of the message body
 */
public record OutreachSentNotice(
        List<Recipient> recipients,
        String senderLabel,
        String subject,
        String snippet
) {

    /**
     * Copies {@code recipients} and refuses a send without one.
     *
     * @throws IllegalArgumentException if {@code recipients} is null or empty
     * @throws NullPointerException if {@code recipients} holds a null element
     */
    public OutreachSentNotice {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("An outreach notice needs at least one recipient");
        }
        recipients = List.copyOf(recipients);
    }

    /**
     * One recipient of the send.
     *
     * @param recipientUserId the member who should receive the notification
     * @param conversationId  the conversation opened for that member, for the UI's deep link
     */
    public record Recipient(int recipientUserId, String conversationId) {
    }
}
