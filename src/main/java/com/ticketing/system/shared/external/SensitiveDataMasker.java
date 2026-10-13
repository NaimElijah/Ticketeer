package com.ticketing.system.shared.external;

/**
 * Masks sensitive values before they reach a log sink.
 *
 * <p>External-service calls (payment, ticket issuance) carry card numbers, CVVs,
 * payment tokens and JWTs. None of those may ever appear in full in a log
 * record. These helpers turn a secret into a short, non-reversible hint that is
 * safe to log while still useful for correlation (last-4 of a card, a token
 * prefix + length).
 *
 * <p>Pure and stateless; every method is null- and short-input-safe and never
 * echoes a value it cannot safely truncate.
 *
 * <p>Pick the right helper for the value:
 * <ul>
 *   <li>{@link #mask(String)} — a raw card number / PAN (keeps last 4).</li>
 *   <li>{@link #maskToken(String)} — an opaque secret: payment token, JWT,
 *       API key (keeps a short prefix + length, never the body).</li>
 *   <li>{@link #truncId(String, int)} — a non-secret identifier (barcode,
 *       transaction id) that is merely long.</li>
 * </ul>
 */
public final class SensitiveDataMasker {

    private static final String REDACTED = "****";

    private SensitiveDataMasker() {
    }

    /**
     * Masks a raw card number / PAN to its last 4 digits, e.g.
     * {@code "4111 1111 1111 1234"} → {@code "**** **** **** 1234"}.
     * Non-digit separators are ignored when locating the last 4. A {@code null}
     * value, or one with fewer than 4 digits, is fully redacted to
     * {@code "****"} so a short/invalid value is never echoed.
     */
    public static String mask(String card) {
        if (card == null) {
            return REDACTED;
        }
        String digits = card.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return REDACTED;
        }
        return "**** **** **** " + digits.substring(digits.length() - 4);
    }

    /**
     * Masks an opaque secret (payment token, JWT, API key) to a short prefix
     * plus its total length, so two records can be correlated without exposing
     * the secret: {@code "tok_4111111111111234"} → {@code "tok_...(20)"}. A
     * {@code null} value, or one too short to reveal a prefix safely (≤ 8
     * chars), is fully redacted.
     */
    public static String maskToken(String token) {
        if (token == null) {
            return REDACTED;
        }
        int len = token.length();
        if (len <= 8) {
            return REDACTED;
        }
        return token.substring(0, 4) + "...(" + len + ")";
    }

    /**
     * Keeps a readable prefix of a non-secret identifier (barcode, transaction
     * id) and ellipsizes the rest, so long issuance payloads don't bloat logs:
     * {@code truncId("QR-7-1c2f...e9", 8)} → {@code "QR-7-1c2..."}. A
     * {@code null} value renders as the literal {@code "null"}; a value already
     * no longer than {@code keep} is returned unchanged.
     */
    public static String truncId(String id, int keep) {
        if (id == null) {
            return "null";
        }
        int cut = Math.max(0, keep);
        if (id.length() <= cut) {
            return id;
        }
        return id.substring(0, cut) + "...";
    }
}
