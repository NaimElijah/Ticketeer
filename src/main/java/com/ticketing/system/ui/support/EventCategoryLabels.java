package com.ticketing.system.ui.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ticketing.system.catalog.domain.EventCategory;

/**
 * The one label each {@link EventCategory} shows in the UI, which is also the label that filters to it
 * (#187, UI-A6). The browse page, the catalogue filters and the landing page all read it, so an event's
 * category reads the same everywhere and its label always selects its own category.
 */
public final class EventCategoryLabels {

    // In the order the category filters list them.
    private static final Map<EventCategory, String> LABELS = labelTable();

    private EventCategoryLabels() { }

    /** @return every category's label, in the order the filters list them */
    public static List<String> labels() {
        return List.copyOf(LABELS.values());
    }

    /**
     * @param category an event category
     * @return its label
     */
    public static String label(EventCategory category) {
        return LABELS.get(category);
    }

    /**
     * The label for a category as a DTO names it.
     *
     * @param categoryName an {@link EventCategory} constant's name, in any case
     * @return its label; {@link EventCategory#OTHER}'s for {@code null} or a name no category has
     */
    public static String labelOf(String categoryName) {
        if (categoryName != null) {
            for (EventCategory category : EventCategory.values()) {
                if (category.name().equalsIgnoreCase(categoryName)) {
                    return label(category);
                }
            }
        }
        return label(EventCategory.OTHER);
    }

    /**
     * @param label a label as {@link #labels()} lists it
     * @return the category it filters to; empty for {@code null} or any other text
     */
    public static Optional<EventCategory> categoryFor(String label) {
        return LABELS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(label))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static Map<EventCategory, String> labelTable() {
        Map<EventCategory, String> labels = new LinkedHashMap<>();
        labels.put(EventCategory.CONCERT, "Concerts");
        labels.put(EventCategory.MUSIC, "Music");
        labels.put(EventCategory.SPORTS, "Sports");
        labels.put(EventCategory.THEATER, "Theatre");
        labels.put(EventCategory.FESTIVAL, "Festivals");
        labels.put(EventCategory.COMEDY, "Comedy");
        labels.put(EventCategory.OTHER, "Other");
        // A category added without a label would be shown as nothing and could not be filtered.
        if (labels.size() != EventCategory.values().length) {
            throw new IllegalStateException("Every EventCategory needs a label; labelled: " + labels.keySet());
        }
        return Collections.unmodifiableMap(labels);
    }
}
