package com.ticketing.system.bootstrap.dev.seed.scenario;

import java.util.List;
import java.util.Map;

/**
 * One parsed line of a {@code .scenario} file: an operation name plus its
 * positional arguments and {@code key=value} named arguments. The line number
 * is kept so a bad live edit reports exactly where it went wrong.
 *
 * <p>Example line {@code add-event u2 p1 e1 standing:30@50 seated:10x10@100 publish=true}
 * parses to op {@code add-event}, positional {@code [u2, p1, e1, standing:30@50,
 * seated:10x10@100]}, named {@code {publish=true}}.
 */
public final class ScenarioCommand {

    private final int line;
    private final String op;
    private final List<String> positional;
    private final Map<String, String> named;

    /**
     * Creates a command; the lists and maps are kept, not copied.
     *
     * @param line       the 1-based line number in the scenario file, used in error messages
     * @param op         the operation name, lower-cased by the parser
     * @param positional the positional arguments in file order, with quotes removed
     * @param named      the {@code key=value} arguments in file order, with quotes removed from values
     */
    public ScenarioCommand(int line, String op, List<String> positional, Map<String, String> named) {
        this.line = line;
        this.op = op;
        this.positional = positional;
        this.named = named;
    }

    public int line() {
        return line;
    }

    public String op() {
        return op;
    }

    public List<String> positional() {
        return positional;
    }

    public Map<String, String> named() {
        return named;
    }

    /** Positional arg at {@code index} (0-based), or {@code null} if absent. */
    public String pos(int index) {
        return index >= 0 && index < positional.size() ? positional.get(index) : null;
    }

    /**
     * Positional arg at {@code index} (0-based).
     *
     * @param what what the argument is, named in the error
     * @throws IllegalArgumentException when the argument is absent or blank; the message names the line,
     *         the operation, {@code what} and the 1-based position
     */
    public String requirePos(int index, String what) {
        String v = pos(index);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                "line " + line + " (" + op + "): missing " + what + " (positional #" + (index + 1) + ")");
        }
        return v;
    }

    /** The named arg {@code key}, or {@code null} if absent. */
    public String named(String key) {
        return named.get(key);
    }

    /** The named arg {@code key}, or {@code fallback} if absent. An empty value counts as present. */
    public String named(String key, String fallback) {
        String v = named.get(key);
        return v == null ? fallback : v;
    }

    /**
     * The named arg {@code key} as an integer, or {@code fallback} if absent.
     *
     * @throws IllegalArgumentException when the value, trimmed, is not an integer; the message names the
     *         line and the operation
     */
    public int intNamed(String key, int fallback) {
        String v = named.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + line + " (" + op + "): " + key + " must be an integer, got '" + v + "'");
        }
    }

    /**
     * The named arg {@code key} as a boolean, or {@code fallback} if absent. A present value is
     * {@code true} only if, trimmed, it is {@code true} in any letter case; anything else is {@code false}.
     */
    public boolean boolNamed(String key, boolean fallback) {
        String v = named.get(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    /**
     * Named arg parsed as a {@code Double}, or {@code null} if absent.
     *
     * @throws IllegalArgumentException when the value, trimmed, is not a finite number; the message names
     *         the line and the operation
     */
    public Double doubleNamed(String key) {
        String v = named.get(key);
        if (v == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(v.trim());
            // Double.parseDouble accepts "NaN"/"Infinity"; reject them so seeded data can't bypass
            // range checks that rely on < / > (both false for NaN) and corrupt ratings downstream.
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException(
                        "line " + line + " (" + op + "): " + key + " must be a finite number, got '" + v + "'");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("line " + line + " (" + op + "): " + key + " must be a number, got '" + v + "'");
        }
    }
}
