package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.BuyerContextDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.RefreshTokenRequestDTO;

/**
 * The sign-in and registration requests carry a raw password. A record's generated {@code toString}
 * prints every component, so one logged request, or one exception message built from it, would put
 * the password in the log (#185). Their {@code toString} must leave the password out, and with it
 * the guest session id, which is the guest's credential, and the email, which is personal data.
 */
class CredentialRequestToStringTest {

    private static final String RAW_PASSWORD = "S3cret-Passw0rd-under-test";
    private static final String GUEST_SESSION_ID = "6d1e0b3a-guest-session-under-test";
    private static final String EMAIL = "alice.under.test@example.com";

    @Test
    void GivenLoginRequest_WhenToString_ThenThePasswordIsAbsent() {
        LoginRequestDTO request = new LoginRequestDTO("alice", RAW_PASSWORD, GUEST_SESSION_ID);

        String rendered = request.toString();

        assertFalse(rendered.contains(RAW_PASSWORD), "LoginRequestDTO.toString() exposed the password: " + rendered);
        assertFalse(rendered.contains(GUEST_SESSION_ID),
                "LoginRequestDTO.toString() exposed the guest session id: " + rendered);
    }

    @Test
    void GivenRegisterRequest_WhenToString_ThenThePasswordIsAbsent() {
        RegisterRequestDTO request = new RegisterRequestDTO("alice", EMAIL, RAW_PASSWORD, GUEST_SESSION_ID, 30);

        String rendered = request.toString();

        assertFalse(rendered.contains(RAW_PASSWORD),
                "RegisterRequestDTO.toString() exposed the password: " + rendered);
        assertFalse(rendered.contains(GUEST_SESSION_ID),
                "RegisterRequestDTO.toString() exposed the guest session id: " + rendered);
        assertFalse(rendered.contains(EMAIL), "RegisterRequestDTO.toString() exposed the email: " + rendered);
    }

    // #187: the published-language records below carry a session token or a guest session id, and a
    // record's generated toString prints it.

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.token-under-test.signature";
    private static final String REFRESH_TOKEN = "refresh-token-under-test";

    @Test
    void GivenAuthToken_WhenToString_ThenTheTokenIsAbsent() {
        AuthTokenDTO token = new AuthTokenDTO(TOKEN, 1_900_000_000_000L, 7, "alice", false);

        String rendered = token.toString();

        assertFalse(rendered.contains(TOKEN), "AuthTokenDTO.toString() exposed the token: " + rendered);
    }

    @Test
    void GivenGuestSession_WhenToString_ThenTheSessionIdIsAbsent() {
        GuestSessionDTO session = new GuestSessionDTO(GUEST_SESSION_ID, Instant.parse("2026-01-01T00:00:00Z"));

        String rendered = session.toString();

        assertFalse(rendered.contains(GUEST_SESSION_ID),
                "GuestSessionDTO.toString() exposed the guest session id: " + rendered);
    }

    @Test
    void GivenGuestBuyerContext_WhenToString_ThenTheSessionIdIsAbsent() {
        BuyerContextDTO buyer = BuyerContextDTO.guest(GUEST_SESSION_ID);

        String rendered = buyer.toString();

        assertFalse(rendered.contains(GUEST_SESSION_ID),
                "BuyerContextDTO.toString() exposed the guest session id: " + rendered);
    }

    @Test
    void GivenGuestActiveOrder_WhenToString_ThenTheSessionIdIsAbsent() {
        ActiveOrderDTO order = new ActiveOrderDTO(null, GUEST_SESSION_ID,
                LocalDateTime.of(2026, 1, 1, 12, 0), 600, 0.0, List.of());

        String rendered = order.toString();

        assertFalse(rendered.contains(GUEST_SESSION_ID),
                "ActiveOrderDTO.toString() exposed the guest session id: " + rendered);
    }

    @Test
    void GivenLogoutRequest_WhenToString_ThenTheTokenIsAbsent() {
        LogoutRequestDTO request = new LogoutRequestDTO(TOKEN);

        String rendered = request.toString();

        assertFalse(rendered.contains(TOKEN), "LogoutRequestDTO.toString() exposed the token: " + rendered);
    }

    @Test
    void GivenMarketControlRequest_WhenToString_ThenTheTokenIsAbsent() {
        MarketControlRequestDTO request = new MarketControlRequestDTO("CLOSE", "maintenance", TOKEN);

        String rendered = request.toString();

        assertFalse(rendered.contains(TOKEN), "MarketControlRequestDTO.toString() exposed the token: " + rendered);
    }

    @Test
    void GivenRefreshTokenRequest_WhenToString_ThenTheRefreshTokenIsAbsent() {
        RefreshTokenRequestDTO request = new RefreshTokenRequestDTO(REFRESH_TOKEN);

        String rendered = request.toString();

        assertFalse(rendered.contains(REFRESH_TOKEN),
                "RefreshTokenRequestDTO.toString() exposed the refresh token: " + rendered);
    }

    // #187: the masked card number and CVV are already left out; the holder name is personal data,
    // and the expiry completes what a leaked card number would need.

    private static final String HOLDER_NAME = "Alice Under-Test";
    private static final int EXPIRY_MONTH = 11;
    private static final int EXPIRY_YEAR = 2031;

    @Test
    void GivenCardDetails_WhenToString_ThenTheHolderNameIsAbsent() {
        String rendered = cardDetails().toString();

        assertFalse(rendered.contains(HOLDER_NAME), "CardDetailsDTO.toString() exposed the holder name: " + rendered);
    }

    @Test
    void GivenCardDetails_WhenToString_ThenTheExpiryIsAbsent() {
        String rendered = cardDetails().toString();

        assertFalse(rendered.contains(String.valueOf(EXPIRY_YEAR)),
                "CardDetailsDTO.toString() exposed the expiry year: " + rendered);
        assertFalse(rendered.contains(String.valueOf(EXPIRY_MONTH)),
                "CardDetailsDTO.toString() exposed the expiry month: " + rendered);
    }

    // The last four digits, 4242, share no digit pair with the expiry, so a masked card number
    // cannot be mistaken for it.
    private static CardDetailsDTO cardDetails() {
        return new CardDetailsDTO("4000 0000 0000 4242", "123", EXPIRY_MONTH, EXPIRY_YEAR, HOLDER_NAME);
    }
}
