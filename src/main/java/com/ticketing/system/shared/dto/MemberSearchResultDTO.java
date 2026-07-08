package com.ticketing.system.shared.dto;

// A single match from the admin "Send Messages" recipient search (search members by username).
public record MemberSearchResultDTO(int memberId, String username) {}
