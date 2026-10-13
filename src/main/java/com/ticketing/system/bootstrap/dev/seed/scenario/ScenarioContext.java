package com.ticketing.system.bootstrap.dev.seed.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running symbol table for a scenario: maps the aliases a file uses
 * ({@code u1}, {@code p1}, {@code e1}) to the real ids/tokens minted by the
 * services. Lookups throw a clear, located error when an alias is unknown, so a
 * mistyped reference in a live-edited file fails loudly instead of silently.
 */
public final class ScenarioContext {

    /** A registered principal — a member or an admin — and their current token (null until login). */
    public static final class Principal {
        public final String alias;
        public final String username;
        public final String password;
        public final int userId;
        public String token;     // set on login, cleared on logout
        public boolean admin;

        Principal(String alias, String username, String password, int userId) {
            this.alias = alias;
            this.username = username;
            this.password = password;
            this.userId = userId;
        }
    }

    private final Map<String, Principal> principals = new LinkedHashMap<>();
    private final Map<String, Integer> companies = new LinkedHashMap<>();
    private final Map<String, Integer> events = new LinkedHashMap<>();
    private final Map<String, String> guests = new LinkedHashMap<>();

    // -- principals ------------------------------------------------------

    public Principal registerPrincipal(String alias, String username, String password, int userId) {
        Principal p = new Principal(alias, username, password, userId);
        principals.put(alias, p);
        return p;
    }

    public Principal principal(String alias) {
        Principal p = principals.get(alias);
        if (p == null) {
            throw new IllegalArgumentException("unknown user alias '" + alias + "' (register it first)");
        }
        return p;
    }

    /** The login token for a member/admin alias; throws if they aren't logged in. */
    public String token(String alias) {
        Principal p = principal(alias);
        if (p.token == null) {
            throw new IllegalArgumentException("user '" + alias + "' is not logged in");
        }
        return p.token;
    }

    public int userId(String alias) {
        return principal(alias).userId;
    }

    public List<Principal> loggedIn() {
        List<Principal> out = new ArrayList<>();
        for (Principal p : principals.values()) {
            if (p.token != null) {
                out.add(p);
            }
        }
        return out;
    }

    // -- guests ----------------------------------------------------------

    public void putGuest(String alias, String sessionId) {
        guests.put(alias, sessionId);
    }

    public boolean isGuest(String alias) {
        return guests.containsKey(alias);
    }

    public String guestSession(String alias) {
        String s = guests.get(alias);
        if (s == null) {
            throw new IllegalArgumentException("unknown guest alias '" + alias + "' (open it with 'guest' first)");
        }
        return s;
    }

    /** A buyer's credential: the guest session id if it's a guest alias, else the member token. */
    public String credential(String alias) {
        return isGuest(alias) ? guestSession(alias) : token(alias);
    }

    // -- companies / events ----------------------------------------------

    public void putCompany(String alias, int companyId) {
        companies.put(alias, companyId);
    }

    public int companyId(String alias) {
        Integer id = companies.get(alias);
        if (id == null) {
            throw new IllegalArgumentException("unknown company alias '" + alias + "' (open it with 'open-company' first)");
        }
        return id;
    }

    public void putEvent(String alias, int eventId) {
        events.put(alias, eventId);
    }

    public int eventId(String alias) {
        Integer id = events.get(alias);
        if (id == null) {
            throw new IllegalArgumentException("unknown event alias '" + alias + "' (create it with 'add-event' first)");
        }
        return id;
    }

    public void clear() {
        principals.clear();
        companies.clear();
        events.clear();
        guests.clear();
    }
}
