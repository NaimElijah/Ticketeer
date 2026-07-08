package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.identity.application.service.AuthenticationService;

// Input to AuthenticationService.login() (UC-12).
//
// guestSessionId is REQUIRED — login is always a promotion of an existing
// Guest session into a Member session (per spec II.1.5 / D10a). A login
// without a guestSessionId raises GuestSessionRequiredException.
public record LoginRequestDTO(
    String username,
    String rawPassword,
    String guestSessionId
) {}
