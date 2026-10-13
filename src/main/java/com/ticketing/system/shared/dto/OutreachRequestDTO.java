package com.ticketing.system.shared.dto;

import java.util.List;

// Input to MessagingService.sendOutreach() (II.6.3.2 — admin proactive messaging).
// The admin targets either an explicit set of members (by id), or all members, or all producers
// (resolved to the owners' member accounts). Each recipient gets a two-way DIRECT conversation.
// allMembers / allProducers may both be set (the recipient sets are unioned and de-duplicated).
public record OutreachRequestDTO(
    String subject,
    String body,
    List<Integer> recipientMemberIds,   // explicit recipients; ignored when a flag is set
    boolean allMembers,                 // true → every registered member
    boolean allProducers                // true → every company owner's member account
) {}
