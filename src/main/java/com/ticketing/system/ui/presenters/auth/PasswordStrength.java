package com.ticketing.system.ui.presenters.auth;

import java.util.EnumSet;
import java.util.Set;

/**
 * Client-side mirror of {@code AuthenticationService.validatePassword}
 * plus extra rules for a "strong" tier.
 *
 * <p>The server is the source of truth — {@link #passesServerRules(String)}
 * matches the server check exactly (≥8 chars, ≥1 letter, ≥1 digit). The
 * extra rules at {@link #STRONG} tier are presentation-only nudges.
 *
 * <p>Used by the {@code PasswordStrengthMeter} component that renders
 * below the password field in {@code RegisterView}.
 */
public enum PasswordStrength {

    /** Empty input — meter renders neutral / hidden. */
    NONE,
    /** Misses one or more of the server-side rules. Won't pass register. */
    WEAK,
    /** Meets the server-side rules. Register succeeds; meter shows amber. */
    FAIR,
    /** Server rules + extra length / variety. Meter shows green. */
    STRONG;

    public enum Rule {
        MIN_8_CHARS, HAS_LETTER, HAS_DIGIT,
        MIN_12_CHARS, HAS_UPPERCASE, HAS_LOWERCASE, HAS_SPECIAL
    }

    /** Set of the server-side rules — failing any of these means WEAK. */
    public static final Set<Rule> SERVER_REQUIRED = EnumSet.of(
        Rule.MIN_8_CHARS, Rule.HAS_LETTER, Rule.HAS_DIGIT
    );

    /** Extra rules that lift FAIR → STRONG. */
    public static final Set<Rule> STRONG_BONUS = EnumSet.of(
        Rule.MIN_12_CHARS, Rule.HAS_UPPERCASE, Rule.HAS_LOWERCASE, Rule.HAS_SPECIAL
    );

    /**
     * Compute the strength tier from a candidate password. Cheap enough
     * to call on every keystroke.
     */
    public static PasswordStrength of(String pw) {
        if (pw == null || pw.isEmpty()) return NONE;
        Set<Rule> met = rulesMet(pw);
        if (!met.containsAll(SERVER_REQUIRED)) return WEAK;
        if (met.containsAll(STRONG_BONUS)) return STRONG;
        return FAIR;
    }

    /** True when the password would pass {@code AuthenticationService.validatePassword}. */
    public static boolean passesServerRules(String pw) {
        return rulesMet(pw).containsAll(SERVER_REQUIRED);
    }

    /** Which rules the input meets — useful for the rule-chip row in the meter. */
    public static Set<Rule> rulesMet(String pw) {
        Set<Rule> met = EnumSet.noneOf(Rule.class);
        if (pw == null) return met;
        int len = pw.length();
        if (len >= 8) met.add(Rule.MIN_8_CHARS);
        if (len >= 12) met.add(Rule.MIN_12_CHARS);
        for (int i = 0; i < len; i++) {
            char c = pw.charAt(i);
            if (Character.isLetter(c)) met.add(Rule.HAS_LETTER);
            if (Character.isDigit(c)) met.add(Rule.HAS_DIGIT);
            if (Character.isUpperCase(c)) met.add(Rule.HAS_UPPERCASE);
            if (Character.isLowerCase(c)) met.add(Rule.HAS_LOWERCASE);
            if (!Character.isLetterOrDigit(c)) met.add(Rule.HAS_SPECIAL);
        }
        return met;
    }

    /** Progress-bar fill value (0..1) matching the tier. */
    public double progressValue() {
        return switch (this) {
            case NONE -> 0.0;
            case WEAK -> 0.33;
            case FAIR -> 0.66;
            case STRONG -> 1.0;
        };
    }

    /** Short label shown next to the bar. */
    public String label() {
        return switch (this) {
            case NONE -> "";
            case WEAK -> "Weak";
            case FAIR -> "Fair";
            case STRONG -> "Strong";
        };
    }

    /** CSS variable name for the bar / label color — uses Lumo defaults. */
    public String cssColorVar() {
        return switch (this) {
            case NONE -> "var(--lumo-contrast-30pct)";
            case WEAK -> "var(--lumo-error-color)";
            case FAIR -> "var(--lumo-warning-color)";
            case STRONG -> "var(--lumo-success-color)";
        };
    }
}
