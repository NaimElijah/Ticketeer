package com.ticketing.system.shared.dto;

// Lightweight Member projection — used as actor info in responses
// and in OrganizationalTreeNodeDTO references.
// Never includes passwordHash, security tokens, or anything sensitive.
public record MemberDTO(
    int userId,
    String username,
    String email
) {}
