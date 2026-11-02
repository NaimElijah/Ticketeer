package com.ticketing.system.ui.layouts;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.dom.Element;

import java.util.Optional;

/**
 * Keyboard and screen-reader focus for the shells (#188; WCAG 2.4.1, 2.4.3;
 * {@code NavigationLandmarksTest}): a "Skip to content" link as a shell's first Tab stop,
 * and, after each navigation, focus on the new page's {@code h1}, so it is announced and
 * focus is not lost when the shell rebuilds its top bar.
 */
final class PageFocus {

    private static final String CONTENT_ID = "lk-content";
    private static final String SKIP_TEXT = "Skip to content";
    private static final String SKIP_CLASS = "lk-skip-link";
    private static final String TABINDEX = "tabindex";
    // Focusable from a script or a link, but not a Tab stop.
    private static final String SCRIPT_FOCUS_ONLY = "-1";
    private static final String HEADING_TAG = "h1";
    // Vaadin's bootstrap page sets <base href>, so the browser would resolve "#lk-content" against the
    // app's root and leave the page; the link moves focus itself instead.
    private static final String FOCUS_TARGET_ON_CLICK = "this.addEventListener('click', e => {"
        + " const target = document.getElementById($0);"
        + " if (target) { e.preventDefault(); target.focus(); } })";

    private PageFocus() {
    }

    /**
     * A link to the page's content, hidden until it has keyboard focus. Add it to the shell's
     * navbar before anything else, so it is the first Tab stop.
     *
     * @return the skip link
     */
    static Anchor skipLink() {
        Anchor skip = new Anchor("#" + CONTENT_ID, SKIP_TEXT);
        skip.addClassName(SKIP_CLASS);
        // The client router would treat the click as a navigation.
        skip.setRouterIgnore(true);
        skip.addAttachListener(e -> skip.getElement().executeJs(FOCUS_TARGET_ON_CLICK, CONTENT_ID));
        return skip;
    }

    /**
     * Marks the page just shown as the skip link's target and moves focus to its first visible
     * {@code h1}, if it has one. Call it from the shell's {@code afterNavigation}.
     *
     * @param content the shell's content, the page shown; {@code null} does nothing
     */
    static void afterNavigation(Component content) {
        if (content == null) {
            return;
        }
        Element page = content.getElement();
        page.setAttribute("id", CONTENT_ID);
        page.setAttribute(TABINDEX, SCRIPT_FOCUS_ONLY);
        firstVisibleHeading(page).ifPresent(h1 -> {
            h1.setAttribute(TABINDEX, SCRIPT_FOCUS_ONLY);
            h1.callJsFunction("focus");
        });
    }

    private static Optional<Element> firstVisibleHeading(Element element) {
        if (element.isTextNode() || !element.isVisible()) {
            return Optional.empty();
        }
        if (HEADING_TAG.equals(element.getTag())) {
            return Optional.of(element);
        }
        return element.getChildren()
            .map(PageFocus::firstVisibleHeading)
            .flatMap(Optional::stream)
            .findFirst();
    }
}
