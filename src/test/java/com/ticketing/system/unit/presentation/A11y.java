package com.ticketing.system.unit.presentation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;

import elemental.json.Json;
import elemental.json.JsonObject;

/**
 * Reads the accessibility contract off a rendered element tree for the scan 7 tests (#188, WCAG 2.2 AA):
 * roles, accessible names, focusability and the keyboard listeners the browser is sent.
 *
 * <p>It approximates what a browser computes from the DOM the server renders, so the tests assert what a
 * screen reader or keyboard user gets rather than which Java class drew it. Roles come from the
 * {@code role} attribute or the tag's implicit role; names follow the order of the accessible name
 * computation (aria-labelledby, aria-label, a field's label, the content for roles named by it, then the
 * title). Content under {@code aria-hidden="true"} never counts. Vaadin web components that are native
 * form controls inside their shadow root ({@code vaadin-button}, {@code vaadin-checkbox}, the fields) are
 * taken as focusable, as the browser makes them.
 */
final class A11y {

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    // Vaadin web components that put a focusable native control in their shadow root.
    private static final Set<String> FOCUSABLE_TAGS = Set.of(
        "button", "input", "select", "textarea",
        "vaadin-button", "vaadin-drawer-toggle", "vaadin-checkbox", "vaadin-radio-button",
        "vaadin-text-field", "vaadin-password-field", "vaadin-email-field", "vaadin-integer-field",
        "vaadin-number-field", "vaadin-big-decimal-field", "vaadin-text-area", "vaadin-select",
        "vaadin-combo-box", "vaadin-multi-select-combo-box", "vaadin-date-picker", "vaadin-time-picker",
        "vaadin-date-time-picker");

    // Tags the browser presses with Enter and Space itself.
    private static final Set<String> NATIVE_BUTTON_TAGS = Set.of("button", "vaadin-button", "vaadin-drawer-toggle");

    // The keys a keydown listener's filter names to press a button, as Flow's key filters spell them.
    private static final Pattern ENTER_KEY = Pattern.compile("['\"]Enter['\"]");
    private static final Pattern SPACE_KEY = Pattern.compile("['\"] ['\"]|['\"]Space(bar)?['\"]");

    // Roles whose accessible name may come from the element's content.
    private static final Set<String> NAMED_FROM_CONTENT = Set.of(
        "button", "checkbox", "link", "menuitem", "menuitemcheckbox", "menuitemradio", "option", "radio",
        "switch", "tab", "heading", "cell", "columnheader", "rowheader", "tooltip", "treeitem");

    private A11y() {
    }

    // ---- the tree ------------------------------------------------------------------------------

    /** Every element under {@code root}, {@code root} included, in document order; text nodes left out. */
    static List<Element> elements(Element root) {
        List<Element> all = new ArrayList<>();
        collect(root, all);
        return all;
    }

    static List<Element> elements(Component root) {
        return elements(root.getElement());
    }

    /** Every element under {@code root} that {@code test} accepts, in document order. */
    static List<Element> elements(Component root, Predicate<Element> test) {
        return elements(root).stream().filter(test).toList();
    }

    /** The element with this {@code id} in the same tree as {@code anywhere}, searched from its top. */
    static Optional<Element> byId(Element anywhere, String id) {
        return elements(top(anywhere)).stream().filter(e -> id.equals(e.getAttribute("id"))).findFirst();
    }

    /** The topmost ancestor of {@code element}. */
    static Element top(Element element) {
        Element top = element;
        while (top.getParent() != null) {
            top = top.getParent();
        }
        return top;
    }

    /** {@code true} when {@code ancestor} is {@code element} or one of its ancestors. */
    static boolean within(Element element, Element ancestor) {
        for (Element e = element; e != null; e = e.getParent()) {
            if (e.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Element element, List<Element> all) {
        if (element.isTextNode()) {
            return;
        }
        all.add(element);
        element.getChildren().forEach(child -> collect(child, all));
    }

    // ---- roles and state -----------------------------------------------------------------------

    /** The element's role: its {@code role} attribute, else the role its tag implies, else "". */
    static String role(Element e) {
        String explicit = e.getAttribute("role");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        return switch (e.getTag()) {
            case "button", "vaadin-button", "vaadin-drawer-toggle" -> "button";
            case "a" -> e.hasAttribute("href") ? "link" : "";
            case "input" -> switch (String.valueOf(e.getAttribute("type"))) {
                case "checkbox" -> "checkbox";
                case "radio" -> "radio";
                default -> "textbox";
            };
            case "vaadin-checkbox" -> "checkbox";
            case "vaadin-radio-button" -> "radio";
            case "vaadin-radio-group" -> "radiogroup";
            case "h1", "h2", "h3", "h4", "h5", "h6" -> "heading";
            case "nav" -> "navigation";
            case "main" -> "main";
            default -> "";
        };
    }

    /** {@code true} when the element is visible, enabled and reachable with Tab. */
    static boolean focusable(Element e) {
        if (!e.isVisible() || disabled(e)) {
            return false;
        }
        Integer tabIndex = tabIndex(e);
        if (tabIndex != null) {
            return tabIndex >= 0;
        }
        return FOCUSABLE_TAGS.contains(e.getTag()) || ("a".equals(e.getTag()) && e.hasAttribute("href"));
    }

    /** {@code true} when the element and every ancestor up to {@code root} is visible, and it is focusable. */
    static boolean inTabOrder(Element e, Element root) {
        for (Element a = e; a != null; a = a.getParent()) {
            if (!a.isVisible()) {
                return false;
            }
            if (a.equals(root)) {
                break;
            }
        }
        return focusable(e);
    }

    static boolean disabled(Element e) {
        return e.hasAttribute("disabled") || e.getProperty("disabled", false) || !e.isEnabled();
    }

    /** {@code true} when assistive technology is told the element is unavailable. */
    static boolean disabledForAt(Element e) {
        return disabled(e) || "true".equals(e.getAttribute("aria-disabled"));
    }

    static Integer tabIndex(Element e) {
        String raw = e.getAttribute("tabindex");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** {@code true} when the element or one of its ancestors is hidden from assistive technology. */
    static boolean ariaHidden(Element e) {
        for (Element a = e; a != null; a = a.getParent()) {
            if ("true".equals(a.getAttribute("aria-hidden"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The polite live region {@code e} sits in, itself included: the nearest element with
     * {@code aria-live="polite"}, or a role that is polite by default ({@code status}, {@code log}). A screen
     * reader announces a change of its content without moving focus.
     */
    static Optional<Element> politeLiveRegion(Element e) {
        for (Element a = e; a != null; a = a.getParent()) {
            String live = a.getAttribute("aria-live");
            if (live != null && !live.isBlank()) {
                return "polite".equals(live.trim()) ? Optional.of(a) : Optional.empty();
            }
            String role = a.getAttribute("role");
            if ("status".equals(role) || "log".equals(role)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** {@code true} when {@code e} is a live region of any politeness, or sits in one. */
    static boolean inLiveRegion(Element e) {
        for (Element a = e; a != null; a = a.getParent()) {
            String live = a.getAttribute("aria-live");
            if (live != null && !live.isBlank()) {
                return !"off".equals(live.trim());
            }
            String role = a.getAttribute("role");
            if ("status".equals(role) || "log".equals(role) || "alert".equals(role) || "timer".equals(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code true} when {@code field} shows a required indicator by any of the means a fix may use: Vaadin's
     * {@code setRequiredIndicatorVisible} (or {@code setRequired}), the web component's {@code required}
     * property, or {@code aria-required="true"}.
     */
    static boolean showsRequired(Component field) {
        return field instanceof HasValue<?, ?> value && value.isRequiredIndicatorVisible()
            || field.getElement().getProperty("required", false)
            || "true".equals(field.getElement().getAttribute("aria-required"));
    }

    /**
     * {@code true} when a script's {@code focus()} on {@code e} takes effect: it is visible and enabled, and
     * natively focusable or carries a {@code tabindex} of any value ({@code -1} included, which makes it
     * focusable from a script without making it a Tab stop). The browser ignores focus on anything else,
     * such as a heading without a {@code tabindex}.
     */
    static boolean scriptFocusable(Element e) {
        if (!e.isVisible() || disabled(e)) {
            return false;
        }
        return tabIndex(e) != null || FOCUSABLE_TAGS.contains(e.getTag())
            || ("a".equals(e.getTag()) && e.hasAttribute("href"));
    }

    /**
     * A focusable element with role button that a keyboard can press: a native or Vaadin button, or another
     * element with {@code role=button} that also handles Enter and Space, as a native button does. A
     * {@code role=button} that only listens for clicks is announced as a button but does nothing on a key.
     */
    static boolean isButton(Element e) {
        if (!"button".equals(role(e)) || !focusable(e)) {
            return false;
        }
        return NATIVE_BUTTON_TAGS.contains(e.getTag()) || (listensForKey(e, ENTER_KEY) && listensForKey(e, SPACE_KEY));
    }

    /** A link the browser follows: an anchor with an href. */
    static boolean isLink(Element e) {
        String href = e.getAttribute("href");
        return "a".equals(e.getTag()) && href != null && !href.isBlank() && focusable(e);
    }

    static boolean isButtonOrLink(Element e) {
        return isButton(e) || isLink(e);
    }

    // ---- names ---------------------------------------------------------------------------------

    /** The element's accessible name, whitespace collapsed; "" when it has none. */
    static String name(Element e) {
        // A Vaadin field's setAriaLabelledBy and setAriaLabel set these properties, which the web component
        // hands to its inner input as aria-labelledby and aria-label.
        String labelledBy = e.hasAttribute("aria-labelledby")
            ? e.getAttribute("aria-labelledby") : e.getProperty("accessibleNameRef", "");
        if (labelledBy != null && !labelledBy.isBlank()) {
            String fromIds = Arrays.stream(labelledBy.trim().split("\\s+"))
                .map(id -> byId(e, id).map(A11y::text).orElse(""))
                .collect(Collectors.joining(" "));
            if (!fromIds.isBlank()) {
                return normalise(fromIds);
            }
        }
        String ariaLabel = e.hasAttribute("aria-label")
            ? e.getAttribute("aria-label") : e.getProperty("accessibleName", "");
        if (ariaLabel != null && !ariaLabel.isBlank()) {
            return normalise(ariaLabel);
        }
        String label = e.hasAttribute("label") ? e.getAttribute("label") : e.getProperty("label", "");
        if (label != null && !label.isBlank()) {
            return normalise(label);
        }
        if ("input".equals(e.getTag())) {
            Optional<String> fromLabel = labelOf(e);
            if (fromLabel.isPresent()) {
                return fromLabel.get();
            }
        }
        if (NAMED_FROM_CONTENT.contains(role(e))) {
            String content = text(e);
            if (!content.isBlank()) {
                return content;
            }
        }
        String title = e.getAttribute("title");
        return title == null ? "" : normalise(title);
    }

    private static Optional<String> labelOf(Element input) {
        for (Element a = input.getParent(); a != null; a = a.getParent()) {
            if ("label".equals(a.getTag())) {
                return Optional.of(text(a)).filter(t -> !t.isBlank());
            }
        }
        String id = input.getAttribute("id");
        if (id == null) {
            return Optional.empty();
        }
        return elements(top(input)).stream()
            .filter(l -> "label".equals(l.getTag()) && id.equals(l.getAttribute("for")))
            .map(A11y::text)
            .filter(t -> !t.isBlank())
            .findFirst();
    }

    /**
     * The text a screen reader reads from {@code e}'s content: text nodes and {@code innerHTML}, minus
     * anything under {@code aria-hidden="true"}; children separated by a space, whitespace collapsed.
     */
    static String text(Element e) {
        StringBuilder out = new StringBuilder();
        appendText(e, out);
        return normalise(out.toString());
    }

    private static void appendText(Element e, StringBuilder out) {
        if (e.isTextNode()) {
            out.append(e.getText());
            return;
        }
        if ("true".equals(e.getAttribute("aria-hidden"))) {
            return;
        }
        if (e.hasProperty("innerHTML")) {
            out.append(' ').append(TAG.matcher(e.getProperty("innerHTML", "")).replaceAll(" ")).append(' ');
        }
        e.getChildren().forEach(child -> {
            appendText(child, out);
            if (!child.isTextNode()) {
                out.append(' ');
            }
        });
    }

    static String normalise(String s) {
        return SPACES.matcher(s.replace(' ', ' ')).replaceAll(" ").trim();
    }

    /** A one-line description of {@code e} for failure messages. */
    static String describe(Element e) {
        StringBuilder d = new StringBuilder("<").append(e.getTag());
        e.getAttributeNames().sorted().forEach(n -> {
            if (!"style".equals(n)) {
                d.append(' ').append(n).append("=\"").append(e.getAttribute(n)).append('"');
            }
        });
        return d.append("> named \"").append(name(e)).append('"').toString();
    }

    // ---- keyboard and DOM events ---------------------------------------------------------------

    /**
     * The expressions of every {@code type} DOM listener on {@code e}: event data and filters, among them
     * the key filter of a keyboard shortcut that listens on this element.
     */
    static Set<String> listenerExpressions(Element e, String type) {
        return e.getNode().getFeature(ElementListenerMap.class).getExpressions(type);
    }

    /** {@code true} when {@code e} has a {@code keydown} listener whose filter or data names {@code key}. */
    static boolean listensForKey(Element e, Pattern key) {
        return listenerExpressions(e, "keydown").stream().anyMatch(x -> key.matcher(x).find());
    }

    /**
     * Fires a DOM event on {@code e} as the browser would report it, with every listener expression true,
     * so a listener whose filter names a key runs as if that key was pressed.
     */
    static void fire(Element e, String type) {
        JsonObject data = Json.createObject();
        listenerExpressions(e, type).forEach(x -> data.put(x, true));
        e.getNode().getFeature(ElementListenerMap.class).fireEvent(new DomEvent(e, type, data));
    }

    /**
     * Activates {@code e} as a click does: through its component's click listeners when it has any (they
     * expect a click's coordinates, which a bare DOM event lacks), else as a DOM click.
     */
    static void activate(Element e) {
        Optional<Component> component = e.getComponent();
        if (component.isPresent() && ComponentUtil.hasEventListener(component.get(), ClickEvent.class)) {
            ComponentUtil.fireEvent(component.get(), new ClickEvent<>(component.get()));
        } else {
            fire(e, "click");
        }
    }

    /** {@code true} when clicking {@code e} does something today: a click listener, or a pointer cursor. */
    static boolean clickable(Element e) {
        Optional<Component> component = e.getComponent();
        return (component.isPresent() && ComponentUtil.hasEventListener(component.get(), ClickEvent.class))
            || !listenerExpressions(e, "click").isEmpty()
            || "pointer".equals(e.getStyle().get("cursor"));
    }

    /**
     * For a failure message: the element a mouse user clicks to reach {@code label} under {@code root} today,
     * that is the nearest clickable ancestor of an element showing it, else the innermost element showing it.
     */
    static String renderedAs(Component root, String label) {
        List<Element> showing = elements(root).stream().filter(e -> text(e).contains(label)).toList();
        for (int i = showing.size() - 1; i >= 0; i--) {
            for (Element a = showing.get(i); a != null; a = a.getParent()) {
                if (clickable(a)) {
                    return describe(a);
                }
                if (a.equals(root.getElement())) {
                    break;
                }
            }
        }
        return showing.isEmpty() ? "nothing" : describe(showing.get(showing.size() - 1));
    }

    /** Fires {@code type} on {@code e} and each ancestor up to {@code root}, as a bubbling event reaches them. */
    static void fireBubbling(Element e, Element root, String type) {
        for (Element a = e; a != null; a = a.getParent()) {
            fire(a, type);
            if (a.equals(root)) {
                return;
            }
        }
    }
}
