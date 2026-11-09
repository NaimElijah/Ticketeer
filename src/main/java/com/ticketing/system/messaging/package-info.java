/**
 * Messaging &amp; Support bounded context.
 *
 * <p>Owns conversations and messages: member&harr;company inquiries, member complaints to the admins,
 * and admin outreach to members. Aggregate: {@code Conversation} (with its {@code Message}s). Hexagonal
 * layout: {@code domain}, {@code application.port.out} (the {@code ConversationRepository} driven
 * port), {@code application.service} ({@code MessagingService}), {@code application.dtoMappers},
 * {@code adapter.out.persistence}.
 *
 * <p>{@code MessagingService} uses identity and organization types directly: the session manager and
 * the user and admin repositories, the company repository and {@code CompanyMembershipService}, and
 * the {@code User} and {@code ProductionCompany} entities and organization's {@code Permission} (#54
 * replaces these with ports). It reaches
 * notifications only through the {@code NewMessageNotice} and {@code OutreachSentNotice} events in
 * {@code shared.event}. The module is declared {@code Type.OPEN}; the module map in
 * {@code HexagonalRulesTest} is the boundary gate (ADR-011).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Messaging & Support", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.messaging;
