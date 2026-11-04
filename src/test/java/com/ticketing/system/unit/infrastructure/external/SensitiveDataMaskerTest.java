package com.ticketing.system.unit.infrastructure.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.external.SensitiveDataMasker;

class SensitiveDataMaskerTest {

    private static final String FULL_PAN = "4111111111111234";

    @Test
    void GivenAFullPan_WhenMask_ThenKeepsOnlyLastFourDigits() {
        assertEquals("**** **** **** 1234", SensitiveDataMasker.mask(FULL_PAN));
    }

    @Test
    void GivenAPanWithSeparators_WhenMask_ThenIgnoresSeparatorsWhenLocatingLastFour() {
        assertEquals("**** **** **** 1234", SensitiveDataMasker.mask("4111 1111 1111 1234"));
    }

    @Test
    void GivenAFullPan_WhenMask_ThenNeverEchoesTheFullPan() {
        assertFalse(SensitiveDataMasker.mask(FULL_PAN).contains(FULL_PAN));
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

    @Test
    void GivenAJwt_WhenMaskToken_ThenKeepsItsPrefixAndNeverEchoesItsSignature() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.s3cr3tS1gnatureV4lue";
        String masked = SensitiveDataMasker.maskToken(jwt);
        assertTrue(masked.startsWith("eyJh"));
        assertFalse(masked.contains("s3cr3tS1gnatureV4lue"));
        assertFalse(masked.contains(jwt));
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
    void GivenANullId_WhenTruncId_ThenRendersNullLiteral() {
        assertEquals("null", SensitiveDataMasker.truncId(null, 8));
    }
}
