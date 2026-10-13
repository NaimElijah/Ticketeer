package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;

import java.util.List;
import java.util.function.Function;

/**
 * Search popover panel — recent-search chip row + grouped result lists.
 *
 * <p>Two flavours:
 * <ul>
 *   <li>the original <b>static</b> {@code (recents, groups)} constructor + {@link #defaults()},
 *       a render-only reference panel; and</li>
 *   <li>the <b>live</b> {@code (recents, searchFn)} constructor (V2-SEARCH-01 / #281) — a debounced
 *       {@link TextField} that calls {@code searchFn} and renders typed rows, an empty state, and a
 *       zero-results state. The kit stays framework-pure: callers inject a
 *       {@code Function<String, List<Row>>} and each {@link Row} carries its own navigation
 *       {@code onPick} {@link Runnable}, so the panel needs no application DTOs.</li>
 * </ul>
 */
public class LkSearchPanel extends Div {

    public record Result(String title, String detail) { }
    public record Group(String label, String iconName, List<Result> items) { }

    /** A live result row: {@code type} drives grouping/icon; {@code onPick} runs on click. */
    public record Row(String type, String title, String subtitle, Runnable onPick) { }

    // ---- static reference panel (render-only) ----
    public LkSearchPanel(List<String> recents, List<Group> groups) {
        addClassName("lk-searchpop");

        Div recentSect = new Div();
        recentSect.addClassName("lk-search-recent");
        recentSect.add(new LkMenu.Label("Recent searches"));
        Div chips = new Div();
        chips.addClassName("lk-recent-chips");
        for (String r : recents) {
            Span chip = new Span();
            chip.addClassName("lk-recent-chip");
            chip.add(new LkIcon("clock", 12));
            chip.add(new Span(r));
            chips.add(chip);
        }
        recentSect.add(chips);
        add(recentSect);

        for (Group g : groups) {
            Div sect = new Div();
            sect.addClassName("lk-search-group");
            sect.add(new LkMenu.Label(g.label));
            for (Result it : g.items) {
                NativeButton row = new NativeButton();
                row.addClassName("lk-search-res");
                Span ico = new Span();
                ico.addClassName("lk-search-res-ic");
                ico.add(new LkIcon(g.iconName, 16));
                Span body = new Span();
                body.addClassName("lk-search-res-body");
                Span t = new Span(it.title);  t.addClassName("lk-search-res-t");
                Span d = new Span(it.detail); d.addClassName("lk-search-res-d");
                body.add(t, d);
                LkIcon go = new LkIcon("arrowRight", 15);
                go.addClassName("lk-search-res-go");
                row.add(ico, body, go);
                sect.add(row);
            }
            add(sect);
        }
    }

    // ---- live, backend-driven panel (#281) ----
    public LkSearchPanel(List<String> recents, Function<String, List<Row>> searchFn) {
        addClassName("lk-searchpop");

        TextField input = new TextField();
        input.addClassName("lk-search-input");
        input.setPlaceholder("Search events, artists, venues…");
        input.setClearButtonVisible(true);
        input.setWidthFull();
        input.setValueChangeMode(ValueChangeMode.LAZY);
        input.setValueChangeTimeout(200);   // 200ms debounce
        input.setPrefixComponent(new LkIcon("search", 16));
        add(input);

        Div results = new Div();
        results.addClassName("lk-search-results");
        add(results);

        input.addValueChangeListener(e -> renderLive(results, input, recents, searchFn, e.getValue()));
        renderLive(results, input, recents, searchFn, "");   // initial: recents / empty hint
    }

    private void renderLive(Div container, TextField input, List<String> recents,
                            Function<String, List<Row>> searchFn, String raw) {
        container.removeAll();
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) {
            renderRecents(container, input, recents);
            return;
        }
        List<Row> rows = searchFn.apply(q);
        if (rows == null || rows.isEmpty()) {
            container.add(emptyState("No matches for “" + q + "”"));
            return;
        }
        renderGroup(container, "Events",  "ticket", rows, "EVENT");
        renderGroup(container, "Artists", "mic",    rows, "ARTIST");
        renderGroup(container, "Venues",  "map",    rows, "VENUE");
    }

    private void renderRecents(Div container, TextField input, List<String> recents) {
        if (recents == null || recents.isEmpty()) {
            container.add(emptyState("Start typing to search events, artists, and venues."));
            return;
        }
        Div sect = new Div();
        sect.addClassName("lk-search-recent");
        sect.add(new LkMenu.Label("Recent searches"));
        Div chips = new Div();
        chips.addClassName("lk-recent-chips");
        for (String r : recents) {
            Span chip = new Span();
            chip.addClassName("lk-recent-chip");
            chip.getStyle().set("cursor", "pointer");
            chip.add(new LkIcon("clock", 12));
            chip.add(new Span(r));
            chip.getElement().addEventListener("click", e -> input.setValue(r)); // re-runs the search
            chips.add(chip);
        }
        sect.add(chips);
        container.add(sect);
    }

    private void renderGroup(Div container, String label, String iconName, List<Row> rows, String type) {
        List<Row> matching = rows.stream().filter(r -> type.equals(r.type())).toList();
        if (matching.isEmpty()) return;

        Div sect = new Div();
        sect.addClassName("lk-search-group");
        sect.add(new LkMenu.Label(label));
        for (Row it : matching) {
            NativeButton row = new NativeButton();
            row.addClassName("lk-search-res");
            Span ico = new Span();
            ico.addClassName("lk-search-res-ic");
            ico.add(new LkIcon(iconName, 16));
            Span body = new Span();
            body.addClassName("lk-search-res-body");
            Span t = new Span(it.title());    t.addClassName("lk-search-res-t");
            Span d = new Span(it.subtitle()); d.addClassName("lk-search-res-d");
            body.add(t, d);
            LkIcon go = new LkIcon("arrowRight", 15);
            go.addClassName("lk-search-res-go");
            row.add(ico, body, go);
            if (it.onPick() != null) {
                row.addClickListener(e -> it.onPick().run());
            }
            sect.add(row);
        }
        container.add(sect);
    }

    private static Div emptyState(String message) {
        Div empty = new Div();
        empty.addClassName("lk-search-empty");
        empty.setText(message);
        return empty;
    }

    public static LkSearchPanel defaults() {
        return new LkSearchPanel(
            List.of("Coldplay", "Hapoel TLV", "Jazz festival"),
            List.of(
                new Group("Events", "ticket", List.of(
                    new Result("Coldplay · Music of the Spheres", "26 Jun · Park HaYarkon"),
                    new Result("Mashina · 35-Year Tour",          "5 Jul · TLV Convention Center")
                )),
                new Group("Artists", "mic", List.of(
                    new Result("Coldplay",   "Concert · 2 upcoming"),
                    new Result("Eden Hason", "Concert · 1 upcoming")
                )),
                new Group("Venues", "map", List.of(
                    new Result("Park HaYarkon",      "Tel Aviv · 6 events"),
                    new Result("Bloomfield Stadium", "Tel Aviv · 3 events")
                ))
            )
        );
    }
}
