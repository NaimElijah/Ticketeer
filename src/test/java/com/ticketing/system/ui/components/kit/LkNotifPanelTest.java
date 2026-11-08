package com.ticketing.system.ui.components.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeButton;

/**
 * The live notification panel, as the bell builds it through {@link LkNotifPanel#fromDTOs}.
 */
class LkNotifPanelTest {

    private static final String VIEW_ALL = "View all notifications";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    // ---- UI-B13: no "View all notifications" link without a page behind it (#187) ----
    // Each test first reads what the panel does show, so an absence it then asserts is not that of an empty
    // panel; and the absence is of the words, whatever control would carry them (#189).

    @Test
    void GivenNotifications_WhenThePanelIsBuilt_ThenItShowsNoViewAllLink() {
        LkNotifPanel panel = LkNotifPanel.fromDTOs(
                List.of(new NotificationDTO("n1", "PURCHASE_CONFIRMED", "SENT", "Your seats are confirmed", CREATED_AT)),
                () -> { }, id -> { }, createdAt -> "5m");

        String shown = panel.getElement().getTextRecursively();
        assertTrue(shown.contains("Your seats are confirmed"), "the panel lists the notification: " + shown);
        assertFalse(shown.contains(VIEW_ALL), "the panel must not offer a link to no page: " + shown);
        assertEquals(List.of(), buttonsReading(panel, VIEW_ALL), "the panel must not offer a link to no page");
    }

    @Test
    void GivenNoNotifications_WhenThePanelIsBuilt_ThenItShowsNoViewAllLink() {
        LkNotifPanel panel = LkNotifPanel.fromDTOs(List.of(), () -> { }, id -> { }, createdAt -> "5m");

        String shown = panel.getElement().getTextRecursively();
        assertTrue(shown.contains("No new notifications"), "the panel says it is empty: " + shown);
        assertFalse(shown.contains(VIEW_ALL), "the panel must not offer a link to no page: " + shown);
        assertEquals(List.of(), buttonsReading(panel, VIEW_ALL), "the panel must not offer a link to no page");
    }

    private static List<String> buttonsReading(Component root, String text) {
        List<String> matches = new ArrayList<>();
        collect(root, text, matches);
        return matches;
    }

    private static void collect(Component component, String text, List<String> matches) {
        if (component instanceof NativeButton button && text.equals(button.getText())) {
            matches.add(button.getText());
        }
        component.getChildren().forEach(child -> collect(child, text, matches));
    }
}
