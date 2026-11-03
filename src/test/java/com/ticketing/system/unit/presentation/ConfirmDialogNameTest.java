package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.kit.LkConfirm;
import com.vaadin.flow.dom.Element;

/**
 * The kit's confirm dialog is named by its title, so a screen reader says what is being confirmed when
 * the dialog opens (#188, A-M10, P-M7, U-A17; WCAG 4.1.2, 1.3.1), for revoking a manager or requesting a
 * refund alike.
 *
 * <p>Vaadin's {@code Dialog} names its overlay from its header title ({@code setHeaderTitle}, the
 * {@code headerTitle} property) or from {@code setAriaLabel} (the {@code ariaLabel} property); either fix
 * passes.
 */
class ConfirmDialogNameTest {

    // ---- F5: LkConfirm accessible name (#188) ----

    private static final String TITLE = "Revoke manager";

    @Test
    void GivenAConfirmation_WhenItIsBuilt_ThenTheDialogIsNamedByItsTitle() {
        LkConfirm confirm = new LkConfirm(TITLE, "Remove Carol from the team?", LkConfirm.Severity.danger);

        assertEquals(TITLE, dialogName(confirm.getElement()),
            "the dialog must be named by its title (header title or aria label)");
    }

    @Test
    void GivenAConfirmationWithTheDefaultSeverity_WhenItIsBuilt_ThenTheDialogIsNamedByItsTitle() {
        LkConfirm confirm = new LkConfirm(TITLE, "Remove Carol from the team?");

        assertEquals(TITLE, dialogName(confirm.getElement()),
            "the dialog must be named by its title (header title or aria label)");
    }

    private static String dialogName(Element dialog) {
        for (String source : new String[] {dialog.getProperty("headerTitle", ""), dialog.getProperty("ariaLabel", ""),
                String.valueOf(dialog.getAttribute("aria-label"))}) {
            if (source != null && !source.isBlank() && !"null".equals(source)) {
                return A11y.normalise(source);
            }
        }
        return "";
    }
}
