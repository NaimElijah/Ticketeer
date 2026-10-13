package com.ticketing.system.bootstrap.dev.seed.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a line-based {@code .scenario} file into {@link ScenarioCommand}s.
 *
 * <p>Format: one operation per line. Blank lines and lines starting with
 * {@code #} (after optional leading whitespace) are ignored, so the file can
 * carry a documentation header and inline comments. Tokens are whitespace-
 * separated, but whitespace inside {@code "double quotes"} is preserved — so a
 * {@code subject="Hello world"} value stays one token. A token containing a
 * top-level {@code =} (outside quotes) is a named argument; everything else is
 * positional. The first token on a line is the operation name.
 */
public final class ScenarioParser {

    public List<ScenarioCommand> parse(String text) {
        List<ScenarioCommand> commands = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String trimmed = raw.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            List<String> tokens = tokenize(raw);
            if (tokens.isEmpty()) {
                continue;
            }
            String op = stripQuotes(tokens.get(0)).toLowerCase();
            List<String> positional = new ArrayList<>();
            Map<String, String> named = new LinkedHashMap<>();
            for (int t = 1; t < tokens.size(); t++) {
                String token = tokens.get(t);
                int eq = topLevelEquals(token);
                if (eq >= 0) {
                    named.put(token.substring(0, eq), stripQuotes(token.substring(eq + 1)));
                } else {
                    positional.add(stripQuotes(token));
                }
            }
            commands.add(new ScenarioCommand(i + 1, op, positional, named));
        }
        return commands;
    }

    /** Split on whitespace, but keep whitespace that sits inside double quotes. */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                sb.append(c);
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (sb.length() > 0) {
                    tokens.add(sb.toString());
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            tokens.add(sb.toString());
        }
        return tokens;
    }

    /** Index of the first {@code =} that is not inside quotes, or -1. */
    private static int topLevelEquals(String token) {
        boolean inQuotes = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '=' && !inQuotes) {
                return i;
            }
        }
        return -1;
    }

    private static String stripQuotes(String s) {
        return s.replace("\"", "");
    }
}
