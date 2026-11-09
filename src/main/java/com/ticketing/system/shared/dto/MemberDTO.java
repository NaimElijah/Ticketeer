package com.ticketing.system.shared.dto;

// A member's id, username and email: the member's own profile, returned by
// MemberQueryService.getMemberProfile. Carries no password hash and no token.
public record MemberDTO(
    int userId,
    String username,
    String email
) {}
