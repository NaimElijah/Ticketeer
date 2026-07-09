package com.ticketing.system.ui.session;

import java.util.ArrayList;
import java.util.List;

import com.vaadin.flow.server.VaadinSession;

/**
 * Session-scoped holder for the visitor's recent top-bar searches (V2-SEARCH-01 / #281).
 *
 * <p>Recent searches are per-user transient state; with no preferences persistence in V2 the
 * VaadinSession is the natural home (mirrors {@link GuestSession} / {@code CurrentCompanies}).
 * The list is most-recent-first, de-duplicated case-insensitively, and capped at {@link #MAX}.
 * All methods are null-safe when there is no live Vaadin session (e.g. unit tests).
 */
public final class RecentSearches {

    private static final String KEY = "recentSearches.list";
    private static final int MAX = 8;

    private RecentSearches() { }

    /** Recent queries, most-recent-first; empty when no session / none recorded. */
    @SuppressWarnings("unchecked")
    public static List<String> get() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return List.of();
        List<String> list = (List<String>) s.getAttribute(KEY);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Records a query at the front (trimmed, de-duped case-insensitively, capped). No-op if blank. */
    @SuppressWarnings("unchecked")
    public static void add(String query) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null || query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;

        List<String> existing = (List<String>) s.getAttribute(KEY);
        List<String> next = new ArrayList<>();
        next.add(q);
        if (existing != null) {
            for (String prev : existing) {
                if (!prev.equalsIgnoreCase(q)) next.add(prev);
                if (next.size() >= MAX) break;
            }
        }
        s.setAttribute(KEY, next);
    }

    public static void clear() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s != null) s.setAttribute(KEY, null);
    }
}
