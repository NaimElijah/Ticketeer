package com.ticketing.system.notifications.application.port.in;

/**
 * One recipient of an admin outreach, as {@link INotificationService#notifyOutreach} takes it. Owned
 * by the notifications port, so the port does not depend on the shape of the notice that carries the
 * send.
 *
 * @param userId         the member who receives the alert
 * @param conversationId the conversation opened for that member, for the alert's deep link
 */
public record OutreachRecipient(int userId, String conversationId) {
}
