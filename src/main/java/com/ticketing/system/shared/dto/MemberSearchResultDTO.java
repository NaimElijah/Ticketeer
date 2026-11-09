package com.ticketing.system.shared.dto;

/**
 * One match from the admin "Send Messages" recipient search, which finds members by username.
 */
public record MemberSearchResultDTO(int memberId, String username) {}
