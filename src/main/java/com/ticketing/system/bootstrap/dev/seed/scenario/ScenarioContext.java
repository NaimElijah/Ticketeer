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
        /** The name the scenario file uses for this principal. */
        public final String alias;
        public final String username;
        public final String password;
        public final int userId;
        /** The current login token: set on login, {@code null} before it and again after logout. */
        public String token;
        /** Whether this principal signed in through {@code login-admin}. */
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

    /**
     * Registers a principal under {@code alias}, replacing any earlier one with that alias. It starts
     * without a token.
     *
     * @return the new principal
     */
    public Principal registerPrincipal(String alias, String username, String password, int userId) {
        Principal p = new Principal(alias, username, password, userId);
        principals.put(alias, p);
        return p;
    }

    /**
     * The principal registered under {@code alias}.
     *
     * @throws IllegalArgumentException when the alias is unknown
     */
    public Principal principal(String alias) {
        Principal p = principals.get(alias);
        if (p == null) {
            throw new IllegalArgumentException("unknown user alias '" + alias + "' (register it first)");
        }
        return p;
    }

    /**
     * The login token for a member/admin alias.
     *
     * @throws IllegalArgumentException when the alias is unknown or the principal is not logged in
     */
    public String token(String alias) {
        Principal p = principal(alias);
        if (p.token == null) {
            throw new IllegalArgumentException("user '" + alias + "' is not logged in");
        }
        return p.token;
    }

    /**
     * The id of the user the alias was registered with.
     *
     * @throws IllegalArgumentException when the alias is unknown
     */
    public int userId(String alias) {
        return principal(alias).userId;
    }

    /** The principals that currently hold a token, in registration order. */
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

    /** Records the guest session id that {@code alias} stands for, replacing an earlier one. */
    public void putGuest(String alias, String sessionId) {
        guests.put(alias, sessionId);
    }

    /** Whether {@code alias} was opened with the {@code guest} operation. */
    public boolean isGuest(String alias) {
        return guests.containsKey(alias);
    }

    /**
     * The guest session id for {@code alias}.
     *
     * @throws IllegalArgumentException when the guest alias is unknown
     */
    public String guestSession(String alias) {
        String s = guests.get(alias);
        if (s == null) {
            throw new IllegalArgumentException("unknown guest alias '" + alias + "' (open it with 'guest' first)");
        }
        return s;
    }

    /**
     * A buyer's credential: the guest session id if it's a guest alias, else the member token.
     *
     * @throws IllegalArgumentException when the alias is neither a guest nor a logged-in principal
     */
    public String credential(String alias) {
        return isGuest(alias) ? guestSession(alias) : token(alias);
    }

    // -- companies / events ----------------------------------------------

    /** Records the company id that {@code alias} stands for, replacing an earlier one. */
    public void putCompany(String alias, int companyId) {
        companies.put(alias, companyId);
    }

    /**
     * The company id for {@code alias}.
     *
     * @throws IllegalArgumentException when the alias is unknown
     */
    public int companyId(String alias) {
        Integer id = companies.get(alias);
        if (id == null) {
            throw new IllegalArgumentException("unknown company alias '" + alias + "' (open it with 'open-company' first)");
        }
        return id;
    }

    /** Records the event id that {@code alias} stands for, replacing an earlier one. */
    public void putEvent(String alias, int eventId) {
        events.put(alias, eventId);
    }

    /**
     * The event id for {@code alias}.
     *
     * @throws IllegalArgumentException when the alias is unknown
     */
    public int eventId(String alias) {
        Integer id = events.get(alias);
        if (id == null) {
            throw new IllegalArgumentException("unknown event alias '" + alias + "' (create it with 'add-event' first)");
        }
        return id;
    }

    /** Forgets every alias, principals, guests, companies and events alike. */
    public void clear() {
        principals.clear();
        companies.clear();
        events.clear();
        guests.clear();
    }
}
