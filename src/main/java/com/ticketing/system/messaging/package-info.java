/**
 * Messaging &amp; Support bounded context.
 *
 * <p>Owns conversations and messages: buyer&harr;company inquiries, complaints, and admin outreach /
 * announcements. Aggregate: {@code Conversation} (with its {@code Message}s). Hexagonal layout:
 * {@code domain}, {@code application.port.out} (the {@code ConversationRepository} driven port),
 * {@code application.service} ({@code MessagingService}), {@code adapter.out.persistence}.
 *
 * <p>Migration note: this step is a mechanical relocation. {@code MessagingService} still references
 * identity, organization, and notification types directly (transitional); module-boundary
 * verification is switched on at Step 10.
 */
package com.ticketing.system.messaging;
