package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * Input to {@code MessagingService.sendOutreach} (II.6.3.2, admin proactive messaging). The admin
 * targets an explicit set of members, all members, or all producers (resolved to the owners' member
 * accounts). Each recipient gets a two-way DIRECT conversation. {@code allMembers} and
 * {@code allProducers} may both be set; the recipient sets are then joined, without duplicates.
 *
 * @param recipientMemberIds the members to message; ignored when either flag is set
 * @param allMembers         whether to message every registered member
 * @param allProducers       whether to message every active company's owners
 */
public record OutreachRequestDTO(
    String subject,
    String body,
    List<Integer> recipientMemberIds,
    boolean allMembers,
    boolean allProducers
) {}
