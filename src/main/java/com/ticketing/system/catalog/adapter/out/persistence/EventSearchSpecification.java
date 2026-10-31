package com.ticketing.system.catalog.adapter.out.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Translates {@link CatalogSearchFiltersDTO} into a Criteria {@link Specification} for the catalogue
 * search (UC-7), mirroring {@code MemoryEventRepository.matchesSearch}. Simple predicates (name,
 * category, rating, location) sit on the Event root; the "at least one matching element" filters
 * (artist, keyword-in-artist, show-date range) are correlated {@code EXISTS} subqueries, and the
 * price range compares against a correlated {@code min(zone price)} subquery (the event's cheapest
 * ticket) so the outer query never multiplies rows (no {@code distinct} needed). A text filter matches
 * its term as a literal substring, ignoring case, as the in-memory adapter does: the LIKE wildcards in
 * the term are escaped.
 */
final class EventSearchSpecification {

    // Escapes the wildcards in a term. Hibernate renders a LIKE without one as escape '' on both H2 and
    // PostgreSQL, so the term's % and _ would stay wildcards; every LIKE here passes this one.
    private static final char LIKE_ESCAPE = '\\';

    private EventSearchSpecification() { }

    static Specification<Event> from(CatalogSearchFiltersDTO filters) {
        return (root, query, cb) -> {
            if (filters == null) {
                return cb.conjunction();
            }
            List<Predicate> predicates = new ArrayList<>();

            if (filters.eventName() != null) {
                predicates.add(containsIgnoringCase(cb, root.get("name"), filters.eventName()));
            }
            if (filters.artistName() != null) {
                predicates.add(cb.exists(artistLike(query, cb, root, filters.artistName())));
            }
            if (filters.category() != null) {
                EventCategory category = parseCategory(filters.category());
                predicates.add(category == null ? cb.disjunction() : cb.equal(root.get("category"), category));
            }
            if (filters.keywords() != null) {
                predicates.add(cb.or(
                        containsIgnoringCase(cb, root.get("name"), filters.keywords()),
                        cb.exists(artistLike(query, cb, root, filters.keywords()))));
            }
            if (filters.fromDate() != null || filters.toDate() != null) {
                predicates.add(cb.exists(showDateInRange(query, cb, root, filters)));
            }
            // The event's CHEAPEST ticket price (min zone price) must fall within the range; a
            // correlated min() subquery returns null for an event with no zones, so it never matches.
            if (filters.minPrice() != null) {
                predicates.add(cb.greaterThanOrEqualTo(cheapestZonePrice(query, cb, root), filters.minPrice()));
            }
            if (filters.maxPrice() != null) {
                predicates.add(cb.lessThanOrEqualTo(cheapestZonePrice(query, cb, root), filters.maxPrice()));
            }
            if (filters.minEventRating() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("rating"), filters.minEventRating()));
            }
            if (filters.maxEventRating() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("rating"), filters.maxEventRating()));
            }
            if (filters.location() != null) {
                Join<Object, Object> venue = root.join("venueMap", JoinType.LEFT);
                predicates.add(cb.or(
                        containsIgnoringCase(cb, venue.get("location").get("city"), filters.location()),
                        containsIgnoringCase(cb, venue.get("location").get("country"), filters.location())));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** {@code value} contains {@code term} as literal text, ignoring case. */
    private static Predicate containsIgnoringCase(CriteriaBuilder cb, Expression<String> value, String term) {
        return cb.like(cb.lower(value), "%" + escapeLikeWildcards(term.toLowerCase(Locale.ROOT)) + "%", LIKE_ESCAPE);
    }

    // The escape character first, so the escapes added for the wildcards are not escaped again.
    private static String escapeLikeWildcards(String term) {
        String escape = String.valueOf(LIKE_ESCAPE);
        return term.replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }

    private static EventCategory parseCategory(String raw) {
        try {
            return EventCategory.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** EXISTS subquery: the outer event has at least one artist matching {@code term}. */
    private static Subquery<Integer> artistLike(CriteriaQuery<?> query, CriteriaBuilder cb, Root<Event> root, String term) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<Event> subRoot = sub.from(Event.class);
        Join<Event, String> artists = subRoot.join("artistsNames");
        sub.select(cb.literal(1)).where(cb.equal(subRoot, root), containsIgnoringCase(cb, artists, term));
        return sub;
    }

    private static Subquery<Integer> showDateInRange(CriteriaQuery<?> query, CriteriaBuilder cb, Root<Event> root,
            CatalogSearchFiltersDTO filters) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<Event> subRoot = sub.from(Event.class);
        Join<Object, Object> showDate = subRoot.join("showDates");
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(subRoot, root));
        if (filters.fromDate() != null) {
            predicates.add(cb.greaterThanOrEqualTo(showDate.get("startTime"), filters.fromDate().atStartOfDay()));
        }
        if (filters.toDate() != null) {
            // The end date runs up to the next midnight, as the in-memory adapter compares whole dates.
            predicates.add(cb.lessThan(showDate.get("startTime"), filters.toDate().plusDays(1).atStartOfDay()));
        }
        sub.select(cb.literal(1)).where(predicates.toArray(new Predicate[0]));
        return sub;
    }

    /** Correlated scalar subquery: the cheapest (min) zone price for the outer event; null if it has no zones. */
    private static Subquery<Double> cheapestZonePrice(CriteriaQuery<?> query, CriteriaBuilder cb, Root<Event> root) {
        Subquery<Double> sub = query.subquery(Double.class);
        Root<Event> subRoot = sub.from(Event.class);
        Join<Object, Object> zone = subRoot.join("venueMap").join("inventoryZones");
        sub.select(cb.min(zone.<Double>get("price"))).where(cb.equal(subRoot, root));
        return sub;
    }
}
