package com.ticketing.system.shared.dto;

// Input for token-refresh flow per lecture 2's JWT pattern.
// UC-12 (token expired → client hits refresh endpoint).
public record RefreshTokenRequestDTO(
    String refreshToken
) {}
