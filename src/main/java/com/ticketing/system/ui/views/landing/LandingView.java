package com.ticketing.system.ui.views.landing;

import com.ticketing.system.Core.Application.dto.EventSummaryDTO;
import com.ticketing.system.Core.Application.dto.ShowDateDTO;
import com.ticketing.system.ui.components.buyer.BzPoster;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Site root — marketing landing. Hero CTA → Browse, category quick
 * links, featured posters, "Why TicketHub" feature row. Reached at
 * {@code /} or by clicking the brand in the top bar.
 */
@Route(value = "", layout = MainLayout.class)
@PageTitle("TicketHub · Find Your Next Experience")
@AnonymousAllowed
public class LandingView extends LkPage {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.ENGLISH);

    private final LandingPresenter presenter;

    public LandingView(LandingPresenter presenter) {
        this.presenter = presenter;

        add(buildHero());
        add(Lk.h2("Browse by Category"));
        add(buildCategoryGrid());
        renderCatalogRows();
        add(Lk.h2("Why TicketHub"));
        add(buildFeatures());
    }

    // ---- hero (decorative banner — no search) ----

    private Component buildHero() {
        Div hero = new Div();
        hero.addClassName("bz-hero");
        Div title = new Div();
        title.addClassName("bz-hero-title");
        title.setText("Find your next unforgettable experience.");
        Div sub = new Div();
        sub.addClassName("bz-hero-sub");
        sub.setText("Concerts, sports, theatre, conferences — across Israel, all in one place.");
        hero.add(title, sub);
        return hero;
    }

    // ---- data-backed catalog rows (V2-LANDING-01) ----

    /** Renders the Featured + "On sale soon" rows from the presenter's outcome. */
    private void renderCatalogRows() {
        switch (presenter.load()) {
            case LandingPresenter.Outcome.Success ok -> {
                add(Lk.h2("Featured This Week"));
                add(ok.featured().isEmpty() ? emptyFeatured() : posterRow(ok.featured()));
                // "On sale soon" is a teaser — show it only when there's something coming up.
                if (!ok.upcoming().isEmpty()) {
                    add(Lk.h2("On Sale Soon"));
                    add(posterRow(ok.upcoming()));
                }
            }
            // The public root degrades gracefully — an empty state, not an error banner.
            case LandingPresenter.Outcome.Failure ignored -> {
                add(Lk.h2("Featured This Week"));
                add(emptyFeatured());
            }
        }
    }

    private Component posterRow(List<EventSummaryDTO> events) {
        Div grid = new Div();
        grid.addClassName("bz-poster-grid");
        events.forEach(e -> grid.add(poster(e)));
        return grid;
    }

    private BzPoster poster(EventSummaryDTO e) {
        return new BzPoster(posterCategory(e.category()), e.name(), meta(e), priceLabel(e))
            .onClick(() -> UI.getCurrent().navigate("events/" + e.eventId()));
    }

    private Component emptyFeatured() {
        Span empty = new Span("No featured events right now — check back soon.");
        empty.getStyle()
            .set("display", "block").set("padding", "32px").set("text-align", "center")
            .set("color", "var(--muted)").set("background", "#fff")
            .set("border", "1px dashed var(--border-strong)").set("border-radius", "12px");
        return empty;
    }

    // ---- poster field mapping ----

    /** Map the domain {@link com.ticketing.system.catalog.domain.EventCategory} name to a
     *  BzPoster category key (drives its gradient + chip label). */
    private static String posterCategory(String category) {
        return switch (category == null ? "" : category.toUpperCase(Locale.ROOT)) {
            case "MUSIC", "CONCERT", "FESTIVAL" -> "Concert";
            case "SPORTS" -> "Sport";
            case "THEATER", "COMEDY" -> "Theatre";
            default -> humanize(category);
        };
    }

    private static String humanize(String name) {
        if (name == null || name.isBlank()) {
            return "Event";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** "Location · 26 Jun · 20:00" — location plus the earliest show date when present. */
    private static String meta(EventSummaryDTO e) {
        String location = e.location() == null ? "" : e.location();
        LocalDateTime first = e.showDates() == null ? null : e.showDates().stream()
            .map(ShowDateDTO::startsAt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
        if (first == null) {
            return location;
        }
        String when = DATE_FMT.format(first);
        return location.isBlank() ? when : location + " · " + when;
    }

    private static String priceLabel(EventSummaryDTO e) {
        return e.minPrice() > 0 ? String.format("From $%,.2f", e.minPrice()) : "—";
    }

    // ---- category quick-pick grid ----

    private Component buildCategoryGrid() {
        Div grid = new Div();
        grid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(min(100%, 220px), 1fr))")
            .set("gap", "16px");
        grid.add(
            categoryCard("Concerts",     "music",   "42 events",  "#8b5cf6", "#ec4899"),
            categoryCard("Sports",       "trophy",  "28 events",  "#0ea5e9", "#10b981"),
            categoryCard("Theatre",      "theater", "14 events",  "#dc2626", "#f59e0b"),
            categoryCard("Conferences",  "mic",     "9 events",   "#0d9488", "#1d4ed8")
        );
        return grid;
    }

    private Div categoryCard(String name, String iconName, String count, String g1, String g2) {
        Div card = new Div();
        card.getStyle()
            .set("background", "linear-gradient(135deg, " + g1 + ", " + g2 + ")")
            .set("border-radius", "12px").set("padding", "22px")
            .set("color", "#fff").set("cursor", "pointer")
            .set("box-shadow", "0 4px 14px -6px rgba(15,23,42,0.25)")
            .set("display", "flex").set("flex-direction", "column").set("gap", "10px");

        Div iconWrap = new Div();
        iconWrap.getStyle()
            .set("width", "44px").set("height", "44px")
            .set("background", "rgba(255,255,255,0.18)")
            .set("border-radius", "10px")
            .set("display", "flex").set("align-items", "center").set("justify-content", "center");
        iconWrap.add(new LkIcon(iconName, 22));

        Span title = new Span(name);
        title.getStyle().set("font-size", "1.1rem").set("font-weight", "800");

        Span sub = new Span(count);
        sub.getStyle().set("font-size", "0.82rem").set("opacity", "0.9");

        card.add(iconWrap, title, sub);
        // Pre-select the category in Browse's filter via query parameter.
        card.getElement().addEventListener("click", e ->
            UI.getCurrent().navigate("browse?category=" + name));
        return card;
    }

    // ---- "Why TicketHub" features ----

    private Component buildFeatures() {
        Div grid = new Div();
        grid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(min(100%, 240px), 1fr))")
            .set("gap", "16px");
        grid.add(
            featureCard("lock",       "Secure & instant",
                "Pay securely and your tickets land in your account in seconds."),
            featureCard("ticket",     "Verified events",
                "Every event on TicketHub is vetted — no scalpers, no surprises."),
            featureCard("comment",    "24/7 support",
                "Real humans, real answers. Reach us by chat or email any time.")
        );
        return grid;
    }

    private Div featureCard(String iconName, String title, String desc) {
        Div card = new Div();
        card.getStyle()
            .set("background", "#fff")
            .set("border", "1px solid var(--border)")
            .set("border-radius", "12px")
            .set("padding", "22px")
            .set("box-shadow", "0 1px 3px rgba(15,23,42,0.04)");

        Div iconWrap = new Div();
        iconWrap.getStyle()
            .set("width", "44px").set("height", "44px")
            .set("border-radius", "10px")
            .set("background", "rgba(26,84,144,0.10)")
            .set("color", "var(--primary)")
            .set("display", "flex").set("align-items", "center").set("justify-content", "center")
            .set("margin-bottom", "12px");
        iconWrap.add(new LkIcon(iconName, 22));

        Span tSpan = new Span(title);
        tSpan.getStyle()
            .set("font-size", "1.05rem").set("font-weight", "800").set("color", "#0f172a")
            .set("display", "block").set("margin-bottom", "6px");

        Span dSpan = new Span(desc);
        dSpan.getStyle()
            .set("font-size", "0.88rem").set("color", "var(--muted)")
            .set("line-height", "1.5").set("display", "block");

        card.add(iconWrap, tSpan, dSpan);
        return card;
    }
}
