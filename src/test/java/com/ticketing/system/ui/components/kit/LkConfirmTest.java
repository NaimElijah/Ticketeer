package com.ticketing.system.ui.components.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;

/**
 * Unit coverage for {@link LkConfirm}'s future contract. The dialog can't be
 * {@code open()}ed without a live UI, so these drive the package-private
 * {@code settle(...)} seam directly — exactly what the Cancel/Confirm buttons
 * and ESC/click-outside dismissal call.
 *
 * <p>The buttons and the dismissal are also driven themselves (#189), by firing the events their listeners
 * take, so a listener that no longer settles the future fails here; and what the builders set is read back.
 */
class LkConfirmTest {

    @Test
    void GivenANewDialog_WhenResultRead_ThenResultFutureStartsIncomplete() {
        LkConfirm dialog = new LkConfirm("Revoke", "Sure?", LkConfirm.Severity.danger);
        assertFalse(dialog.result().isDone(), "future must not resolve before a decision");
    }

    @Test
    void GivenAnUnsettledDialog_WhenSettleTrue_ThenCompletesFutureWithTrue() {
        LkConfirm dialog = new LkConfirm("Revoke", "Sure?", LkConfirm.Severity.danger)
            .confirmText("Revoke");

        dialog.settle(true);

        assertTrue(dialog.result().isDone());
        assertEquals(Boolean.TRUE, dialog.result().getNow(null));
    }

    @Test
    void GivenAnUnsettledDialog_WhenSettleFalse_ThenCompletesFutureWithFalse() {
        LkConfirm dialog = new LkConfirm("Clear cart", "Remove all items?", LkConfirm.Severity.warn);

        dialog.settle(false);

        assertTrue(dialog.result().isDone());
        assertEquals(Boolean.FALSE, dialog.result().getNow(null));
    }

    @Test
    void GivenADialogSettledTrue_WhenSettleFalse_ThenSettleIsIdempotentAndFirstDecisionWins() {
        LkConfirm dialog = new LkConfirm("Heads up", "Proceed?", LkConfirm.Severity.info);

        dialog.settle(true);
        dialog.settle(false); // a later dismissal must not overwrite the confirm

        assertEquals(Boolean.TRUE, dialog.result().getNow(null));
    }

    // ---- #189: the buttons, the dismissal and the builders ----

    @Test
    void GivenAnUnsettledDialog_WhenCancelIsClicked_ThenTheFutureCompletesWithFalse() {
        LkConfirm dialog = new LkConfirm("Revoke", "Sure?", LkConfirm.Severity.danger).confirmText("Revoke");

        clickFooterButton(dialog, "Cancel");

        assertEquals(Boolean.FALSE, dialog.result().getNow(null));
    }

    @Test
    void GivenAnUnsettledDialog_WhenTheConfirmButtonIsClicked_ThenTheFutureCompletesWithTrue() {
        LkConfirm dialog = new LkConfirm("Revoke", "Sure?", LkConfirm.Severity.danger).confirmText("Revoke");

        clickFooterButton(dialog, "Revoke");

        assertEquals(Boolean.TRUE, dialog.result().getNow(null));
    }

    @Test
    void GivenAnUnsettledDialog_WhenDismissedWithEscOrAClickOutside_ThenTheFutureCompletesWithFalse() {
        LkConfirm dialog = new LkConfirm("Clear cart", "Remove all items?", LkConfirm.Severity.warn);

        ComponentUtil.fireEvent(dialog, new Dialog.DialogCloseActionEvent(dialog, true));

        assertEquals(Boolean.FALSE, dialog.result().getNow(null));
    }

    @Test
    void GivenConfirmAndCancelTexts_WhenBuilt_ThenTheFooterButtonsReadThemCancelFirst() {
        LkConfirm dialog = new LkConfirm("Clear cart", "Remove all items?", LkConfirm.Severity.warn)
            .confirmText("Clear")
            .cancelText("Keep");

        assertEquals(List.of("Keep", "Clear"), footerButtons(dialog).stream().map(Button::getText).toList());
    }

    @Test
    void GivenDangerSeverity_WhenBuilt_ThenTheConfirmButtonIsPrimaryAndRed() {
        Button confirm = footerButton(new LkConfirm("Revoke", "Sure?", LkConfirm.Severity.danger), "Confirm");

        assertTrue(confirm.hasThemeName(ButtonVariant.LUMO_PRIMARY.getVariantName()));
        assertTrue(confirm.hasThemeName(ButtonVariant.LUMO_ERROR.getVariantName()));
    }

    // A dialog built as danger and then made milder keeps no red confirm.
    @Test
    void GivenADangerDialogMadeInfo_WhenBuilt_ThenTheConfirmButtonIsPrimaryAndNotRed() {
        Button confirm = footerButton(new LkConfirm("Heads up", "Proceed?", LkConfirm.Severity.danger)
            .severity(LkConfirm.Severity.info), "Confirm");

        assertTrue(confirm.hasThemeName(ButtonVariant.LUMO_PRIMARY.getVariantName()));
        assertFalse(confirm.hasThemeName(ButtonVariant.LUMO_ERROR.getVariantName()));
    }

    // The title and body are set as HTML, so markup in them (a company or event name) must arrive as text.
    @Test
    void GivenATitleAndBodyWithMarkup_WhenBuilt_ThenTheyAreEscapedAndTheBodysLineBreaksKept() {
        LkConfirm dialog = new LkConfirm("Cancel <b>Acme</b> & co", "<img src=x onerror=alert(1)>\nfor good",
            LkConfirm.Severity.danger);

        List<String> html = innerHtml(dialog);

        assertTrue(html.contains("<b>Cancel &lt;b&gt;Acme&lt;/b&gt; &amp; co</b>"), "the title, escaped: " + html);
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;<br>for good"), "the body, escaped: " + html);
    }

    @Test
    void GivenEverySeverity_WhenBuildersChained_ThenBuildersAreChainableAndResultFutureStaysIncomplete() {
        for (LkConfirm.Severity sev : LkConfirm.Severity.values()) {
            LkConfirm dialog = new LkConfirm("Title", "Body", sev)
                .severity(sev)
                .confirmText("Yes")
                .cancelText("No")
                .addToBody(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 16), "context"));
            assertFalse(dialog.result().isDone());
        }
    }

    // A dialog's footer is not among its component children, so walk the footer's own elements.
    private static List<Button> footerButtons(LkConfirm dialog) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialog.getFooter().getElement(), buttons);
        return buttons;
    }

    private static void collectButtons(Element element, List<Button> found) {
        element.getComponent().filter(Button.class::isInstance).map(Button.class::cast).ifPresent(found::add);
        element.getChildren().forEach(child -> collectButtons(child, found));
    }

    private static Button footerButton(LkConfirm dialog, String text) {
        return footerButtons(dialog).stream()
            .filter(button -> text.equals(button.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no footer button \"" + text + "\""));
    }

    // The dialog cannot open without a live UI, so the click is fired on the button as its listener takes it.
    private static void clickFooterButton(LkConfirm dialog, String text) {
        Button button = footerButton(dialog, text);
        ComponentUtil.fireEvent(button, new ClickEvent<>(button));
    }

    private static List<String> innerHtml(LkConfirm dialog) {
        List<String> html = new ArrayList<>();
        dialog.getChildren().forEach(child -> collectInnerHtml(child.getElement(), html));
        return html;
    }

    private static void collectInnerHtml(Element element, List<String> found) {
        if (element.getComponent().filter(Span.class::isInstance).isPresent() && element.hasProperty("innerHTML")) {
            found.add(element.getProperty("innerHTML"));
        }
        element.getChildren().forEach(child -> collectInnerHtml(child, found));
    }
}
