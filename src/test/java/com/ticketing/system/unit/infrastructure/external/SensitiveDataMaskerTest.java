package com.ticketing.system.unit.infrastructure.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.external.SensitiveDataMasker;

class SensitiveDataMaskerTest {

    private static final String FULL_PAN = "4111111111111234";
    private static final String JWT_HEADER = "eyJhbGciOiJIUzI1NiJ9";
    private static final String JWT_PAYLOAD = "eyJzdWIiOiJ1c2VyIn0";
    private static final String JWT_SIGNATURE = "s3cr3tS1gnatureV4lue";

    @Test
    void GivenAFullPan_WhenMask_ThenKeepsOnlyLastFourDigits() {
        assertEquals("**** **** **** 1234", SensitiveDataMasker.mask(FULL_PAN));
    }

    @Test
    void GivenAPanWithSeparators_WhenMask_ThenIgnoresSeparatorsWhenLocatingLastFour() {
        assertEquals("**** **** **** 1234", SensitiveDataMasker.mask("4111 1111 1111 1234"));
    }

    @Test
    void GivenNullOrTooShortPan_WhenMask_ThenRedacts() {
        assertEquals("****", SensitiveDataMasker.mask(null));
        assertEquals("****", SensitiveDataMasker.mask("12"));
        assertEquals("****", SensitiveDataMasker.mask(""));
    }

    @Test
    void GivenAPaymentToken_WhenMaskToken_ThenKeepsShortPrefixAndLengthOnly() {
        // tok_<pan> is how the checkout builds the payment token — the prefix is
        // safe, the embedded PAN must not survive masking.
        String token = "tok_" + FULL_PAN; // length 20
        assertEquals("tok_...(20)", SensitiveDataMasker.maskToken(token));
        assertFalse(SensitiveDataMasker.maskToken(token).contains(FULL_PAN));
    }

    // The payload carries the claims (subject, session id, role), so it must go as surely as the signature.
    @Test
    void GivenAJwt_WhenMaskToken_ThenKeepsOnlyItsFirstFourCharactersAndItsLength() {
        String jwt = JWT_HEADER + "." + JWT_PAYLOAD + "." + JWT_SIGNATURE;

        String masked = SensitiveDataMasker.maskToken(jwt);

        assertEquals("eyJh...(" + jwt.length() + ")", masked);
        assertFalse(masked.contains(JWT_PAYLOAD), "the claims must not survive masking: " + masked);
        assertFalse(masked.contains(JWT_SIGNATURE), "the signature must not survive masking: " + masked);
    }

    @Test
    void GivenExactlyFourDigits_WhenMask_ThenTheyAreKept() {
        assertEquals("**** **** **** 1234", SensitiveDataMasker.mask("1234"));
    }

    @Test
    void GivenThreeDigits_WhenMask_ThenRedacts() {
        assertEquals("****", SensitiveDataMasker.mask("123"));
    }

    @Test
    void GivenANineCharacterToken_WhenMaskToken_ThenKeepsItsPrefixAndLength() {
        assertEquals("abcd...(9)", SensitiveDataMasker.maskToken("abcdefghi"));
    }

    @Test
    void GivenNullOrTooShortToken_WhenMaskToken_ThenRedacts() {
        assertEquals("****", SensitiveDataMasker.maskToken(null));
        assertEquals("****", SensitiveDataMasker.maskToken("12345678"));
    }

    @Test
    void GivenAnIdLongerThanTheLimit_WhenTruncId_ThenKeepsPrefixAndEllipsizesTheRest() {
        assertEquals("QR-7-...", SensitiveDataMasker.truncId("QR-7-abcdef-uuid", 5));
    }

    @Test
    void GivenAnIdShorterThanTheLimit_WhenTruncId_ThenReturnsTheValueUnchanged() {
        assertEquals("QR-7", SensitiveDataMasker.truncId("QR-7", 8));
    }

    @Test
    void GivenAnIdExactlyAsLongAsTheLimit_WhenTruncId_ThenReturnsTheValueUnchanged() {
        assertEquals("QR-7-abc", SensitiveDataMasker.truncId("QR-7-abc", 8));
    }

    @Test
    void GivenANullId_WhenTruncId_ThenRendersNullLiteral() {
        assertEquals("null", SensitiveDataMasker.truncId(null, 8));
    }
}
