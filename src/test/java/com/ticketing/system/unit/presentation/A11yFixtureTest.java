package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;

/**
 * The scan 7 fixtures count what a browser would (#188, review T2, T5, T6): a focus call counts only for
 * the element the script focuses, and only when that element can take focus; a {@code role=button} that a
 * keyboard cannot press is no button. Without these, a focus test passes on a call the browser ignores,
 * and a button test passes on a span that only listens for clicks.
 *
 * <p>They also click only what shows (#189): a click or an activation fails on a hidden or disabled control,
 * which no visitor can use.
 */
class A11yFixtureTest {

    // ---- review T2, T5, T6: the fixtures count focus and buttons as a browser does (#188) ----

    private ViewHarness harness;
    private Div page;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        page = new Div();
        harness.attach(page);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- focusTargets: which element a call focuses ----

    @Test
    void GivenAButton_WhenItsFocusIsCalled_ThenTheButtonIsTheFocusTarget() {
        NativeButton button = new NativeButton("Save");
        page.add(button);

        button.focus();

        assertEquals(List.of(button.getElement()), harness.focusTargets());
    }

    @Test
    void GivenAScriptThatFocusesItsSecondParameter_WhenItRuns_ThenThatElementIsTheFocusTarget() {
        NativeButton owner = new NativeButton("Owner");
        NativeButton other = new NativeButton("Other");
        page.add(owner, other);

        owner.getElement().executeJs("$1.focus()", owner.getElement(), other.getElement());

        assertEquals(List.of(other.getElement()), harness.focusTargets(),
            "a script focusing $1 focuses its second parameter, not the element that runs it");
    }

    @Test
    void GivenAScriptThatLooksUpWhatItFocuses_WhenItRuns_ThenItIsNoFocusCall() {
        NativeButton skip = new NativeButton("Skip to content");
        page.add(skip);

        skip.getElement().executeJs("this.addEventListener('click', e => {"
            + " const target = document.getElementById($0); if (target) { target.focus(); } })", "lk-content");

        assertEquals(List.of(), harness.focusTargets(),
            "a listener that will focus an element it looks up later is no focus call now");
    }

    // ---- focusTargets: the target must be able to take focus ----

    @Test
    void GivenAHeadingWithoutATabindex_WhenItsFocusIsCalled_ThenTheCallDoesNotCount() {
        H2 heading = new H2("Refund delay");
        page.add(heading);

        heading.getElement().callJsFunction("focus");

        assertEquals(List.of(), harness.focusTargets(), "a browser ignores focus on a heading with no tabindex");
    }

    @Test
    void GivenAHeadingFocusableFromAScript_WhenItsFocusIsCalled_ThenTheCallCounts() {
        H2 heading = new H2("Refund delay");
        heading.getElement().setAttribute("tabindex", "-1");
        page.add(heading);

        heading.getElement().callJsFunction("focus");

        assertEquals(List.of(heading.getElement()), harness.focusTargets());
    }

    @Test
    void GivenADisabledButton_WhenItsFocusIsCalled_ThenTheCallDoesNotCount() {
        NativeButton button = new NativeButton("Pay");
        button.setEnabled(false);
        page.add(button);

        button.focus();

        assertEquals(List.of(), harness.focusTargets(), "a browser does not focus a disabled button");
    }

    // ---- isButton ----

    @Test
    void GivenASpanWithRoleButtonThatOnlyListensForClicks_WhenChecked_ThenItIsNoButton() {
        Span span = roleButtonSpan();
        span.addClickListener(e -> { });

        assertFalse(A11y.isButton(span.getElement()),
            "Enter and Space do nothing on a span with only a click listener, so it is no button");
    }

    @Test
    void GivenASpanWithRoleButtonThatHandlesEnterAndSpace_WhenChecked_ThenItIsAButton() {
        Span span = roleButtonSpan();
        span.getElement().addEventListener("keydown", e -> { })
            .setFilter("event.key === 'Enter' || event.key === ' '");

        assertTrue(A11y.isButton(span.getElement()));
    }

    @Test
    void GivenANativeButton_WhenChecked_ThenItIsAButtonWithoutKeyListeners() {
        Element button = new NativeButton("Save").getElement();

        assertTrue(A11y.isButton(button), "the browser presses a native button with Enter and Space itself");
    }

    // ---- #189: a click needs a control that shows and is enabled ----

    @Test
    void GivenAShownEnabledButton_WhenClicked_ThenItsListenerRuns() {
        AtomicInteger clicks = new AtomicInteger();
        NativeButton button = new NativeButton("Pay", e -> clicks.incrementAndGet());
        page.add(button);

        ViewHarness.click(button);

        assertEquals(1, clicks.get(), "a click on a shown, enabled button must reach its listener");
    }

    @Test
    void GivenAHiddenButton_WhenClicked_ThenTheClickFailsAndItsListenerNeverRuns() {
        AtomicInteger clicks = new AtomicInteger();
        NativeButton button = new NativeButton("Pay", e -> clicks.incrementAndGet());
        button.setVisible(false);
        page.add(button);

        assertThrows(AssertionError.class, () -> ViewHarness.click(button), "no visitor can click a hidden button");
        assertEquals(0, clicks.get(), "a refused click must not reach the listener");
    }

    @Test
    void GivenAButtonInAHiddenContainer_WhenClicked_ThenTheClickFails() {
        Div container = new Div();
        container.setVisible(false);
        NativeButton button = new NativeButton("Pay", e -> { });
        container.add(button);
        page.add(container);

        assertThrows(AssertionError.class, () -> ViewHarness.click(button),
            "a button inside a hidden container does not show, so no visitor can click it");
    }

    @Test
    void GivenADisabledButton_WhenClicked_ThenTheClickFails() {
        NativeButton button = new NativeButton("Pay", e -> { });
        button.setEnabled(false);
        page.add(button);

        assertThrows(AssertionError.class, () -> ViewHarness.click(button),
            "the browser sends no click on a disabled button");
    }

    @Test
    void GivenAButtonInADisabledContainer_WhenClicked_ThenTheClickFails() {
        Div container = new Div();
        NativeButton button = new NativeButton("Pay", e -> { });
        container.add(button);
        container.setEnabled(false);
        page.add(container);

        assertThrows(AssertionError.class, () -> ViewHarness.click(button),
            "a button inside a disabled container is disabled too");
    }

    @Test
    void GivenADisabledButton_WhenForceClicked_ThenItsListenerRuns() {
        AtomicInteger clicks = new AtomicInteger();
        NativeButton button = new NativeButton("Pay", e -> clicks.incrementAndGet());
        button.setEnabled(false);
        page.add(button);

        ViewHarness.forceClick(button);

        assertEquals(1, clicks.get(), "a forced click stands in for a crafted request, so it reaches the listener");
    }

    @Test
    void GivenAHiddenButton_WhenTheInboxHarnessClicksIt_ThenTheClickFails() {
        NativeButton button = new NativeButton("Open", e -> { });
        button.setVisible(false);
        page.add(button);

        assertThrows(AssertionError.class, () -> InboxViewHarness.click(button));
    }

    @Test
    void GivenAHiddenElementWithAClickListener_WhenDomClicked_ThenTheClickFails() {
        Div card = new Div();
        card.getElement().addEventListener("click", e -> { });
        card.setVisible(false);
        page.add(card);

        assertThrows(AssertionError.class, () -> ViewHarness.domClick(card.getElement()));
    }

    @Test
    void GivenAShownSpanWithAClickListener_WhenActivated_ThenItsListenerRuns() {
        AtomicInteger clicks = new AtomicInteger();
        Span span = roleButtonSpan();
        span.addClickListener(e -> clicks.incrementAndGet());
        page.add(span);

        A11y.activate(span.getElement());

        assertEquals(1, clicks.get(), "activating a shown, enabled control must reach its listener");
    }

    @Test
    void GivenAHiddenSpanWithAClickListener_WhenActivated_ThenTheActivationFails() {
        Span span = roleButtonSpan();
        span.addClickListener(e -> { });
        span.setVisible(false);
        page.add(span);

        assertThrows(AssertionError.class, () -> A11y.activate(span.getElement()));
    }

    @Test
    void GivenADisabledElementWithADomClickListener_WhenActivated_ThenTheActivationFails() {
        Div card = new Div();
        card.getElement().addEventListener("click", e -> { });
        card.setEnabled(false);
        page.add(card);

        assertThrows(AssertionError.class, () -> A11y.activate(card.getElement()));
    }

    private static Span roleButtonSpan() {
        Span span = new Span("Open");
        span.getElement().setAttribute("role", "button");
        span.getElement().setAttribute("tabindex", "0");
        return span;
    }
}
