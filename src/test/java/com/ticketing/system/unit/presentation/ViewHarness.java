package com.ticketing.system.unit.presentation;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import com.ticketing.system.organization.application.dto.MembershipRole;
import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CompanyManagementBridge;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.internal.ExecutionContext;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

import elemental.json.Json;

/**
 * Builds views and layouts headless for the view-defect tests of scan 6 (#187), on the approach of
 * {@link InboxViewHarness}: a current {@link UI} that records what components ask to run before the
 * next response, so toasts and dialogs attach when {@link #toasts()} or {@link #dialogs()} runs them.
 *
 * <p>On top of that it records navigations instead of routing them, installs a map-backed
 * {@code VaadinSession} for the session holders, and a {@link VaadinService} whose router answers every
 * route with a stub URL, so a view or layout that builds a {@code RouterLink} can be constructed. It can
 * also sign a member or an admin in, and make the member a company's founder or manager through
 * {@link CompanyManagementBridge}, which it clears while open and restores on {@link #close()}.
 * Create one per test.
 */
final class ViewHarness implements AutoCloseable {

    static final String MEMBER_TOKEN = "member-jwt";
    static final String ADMIN_TOKEN = "admin-jwt";
    static final int MEMBER_ID = 42;
    static final int ADMIN_ID = 1;

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    // A script focusing its Nth parameter: Focusable.focus() and callJsFunction("focus") both send one.
    private static final Pattern FOCUS_CALL = Pattern.compile("\\$(\\d+)\\.focus\\(");

    private RecordingUi ui = new RecordingUi();
    // CurrentInstance keeps the current service weakly; this keeps it alive for the test.
    private final VaadinService service = stubRouterService();
    private final List<List<Object>> javaScript = new ArrayList<>();
    private final List<Element> focused = new ArrayList<>();
    private CompanyManagementService companyManagementService;
    // The bridge is static, and a Spring context closed by an earlier test class leaves its dead service
    // in it, so a view test must never inherit what it holds.
    private final CompanyManagementService bridgedBefore = CompanyManagementBridge.service();

    ViewHarness() {
        new CompanyManagementBridge(null);
        MapBackedVaadinSession.install();
        VaadinService.setCurrent(service);
        UI.setCurrent(ui);
        ensureUiSession();
    }

    // ---- who is signed in ------------------------------------------------------------------

    static void signInMember() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "alice"));
    }

    static void signInAdmin() {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, 0L, ADMIN_ID, "admin", true));
    }

    /**
     * Gives the signed-in member these memberships, as {@code CompanyManagementService.listForUser}
     * answers them to {@code Capabilities}.
     */
    CompanyManagementService memberships(UserCompanyDTO... memberships) {
        if (companyManagementService == null) {
            companyManagementService = mock(CompanyManagementService.class);
            new CompanyManagementBridge(companyManagementService);
        }
        when(companyManagementService.listForUser(anyString())).thenReturn(List.of(memberships));
        return companyManagementService;
    }

    static UserCompanyDTO founderOf(int companyId, String name) {
        return new UserCompanyDTO(companyId, name, "desc", "", MembershipRole.FOUNDER, "ACTIVE", 1, 0, List.of());
    }

    static UserCompanyDTO managerOf(int companyId, String name, Permission... permissions) {
        return new UserCompanyDTO(companyId, name, "desc", "", MembershipRole.MANAGER, "ACTIVE", 1, 0,
            List.of(permissions));
    }

    // ---- navigation --------------------------------------------------------------------------

    /** A before-enter event for a route with these {@code name, value} parameters and no query. */
    static BeforeEnterEvent routeTo(String... nameValuePairs) {
        return routeTo(QueryParameters.empty(), nameValuePairs);
    }

    /** A before-enter event for a route with these {@code name, value} parameters and this query. */
    static BeforeEnterEvent routeTo(QueryParameters query, String... nameValuePairs) {
        BeforeEnterEvent event = mock(BeforeEnterEvent.class);
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            params.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        when(event.getRouteParameters()).thenReturn(new RouteParameters(params));
        when(event.getLocation()).thenReturn(new Location("stub", query));
        return event;
    }

    /**
     * Runs {@code action} each time a view navigates, at the moment it asks, as the router builds the target
     * and runs its layout's {@code afterNavigation} before the navigating code goes on.
     */
    void onNavigation(Runnable action) {
        ui.onNavigation.add(action);
    }

    /** Every navigation the views asked for, in order, as a target class name or a location string. */
    List<String> navigations() {
        return List.copyOf(ui.navigations);
    }

    // ---- what the visitor sees ---------------------------------------------------------------

    /** The text of each toast shown so far, in the order shown. */
    List<String> toasts() {
        ui.runRecorded();
        return find(ui, Notification.class).stream()
            .map(ViewHarness::toastText)
            .toList();
    }

    /** Every dialog opened so far and still attached, Vaadin's confirm dialog included. */
    List<Component> dialogs() {
        ui.runRecorded();
        List<Component> found = new ArrayList<>(find(ui, Dialog.class));
        found.addAll(find(ui, ConfirmDialog.class));
        return found;
    }

    /**
     * Confirms the open confirmation, whichever kind it is: the kit's {@code LkConfirm} by clicking
     * its confirm button, or Vaadin's {@link ConfirmDialog} by firing its confirm event.
     *
     * @return {@code true} when there was a confirmation to confirm
     */
    boolean confirmOpenDialog() {
        for (Component dialog : dialogs()) {
            if (dialog instanceof ConfirmDialog confirm) {
                ComponentUtil.fireEvent(confirm, new ConfirmDialog.ConfirmEvent(confirm, false));
                return true;
            }
            List<Button> buttons = dialog instanceof Dialog d ? footerButtons(d) : List.of();
            if (!buttons.isEmpty()) {
                // The kit puts Cancel first and the confirm button last.
                click(buttons.get(buttons.size() - 1));
                return true;
            }
        }
        return false;
    }

    /** Attaches {@code component} to this UI, as the router does with the view it shows. */
    void attach(Component component) {
        ui.add(component);
    }

    // A queued JavaScript call, the page's reload included, is checked against the UI's session lock,
    // so the UI gets a session that reports the lock as held.
    private void ensureUiSession() {
        VaadinSession uiSession = mock(VaadinSession.class);
        when(uiSession.hasLock()).thenReturn(true);
        ui.getInternals().setSession(uiSession);
    }

    /**
     * The parameters of every JavaScript call queued so far by attached components, the page's
     * included, in order. Each entry is the call's expression followed by its parameters.
     */
    List<List<Object>> javaScriptCalls() {
        ui.runRecorded();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        for (PendingJavaScriptInvocation pending : ui.getInternals().dumpPendingJavaScriptInvocations()) {
            List<Object> call = new ArrayList<>();
            call.add(pending.getInvocation().getExpression());
            call.addAll(pending.getInvocation().getParameters());
            javaScript.add(call);
            focusTarget(call).ifPresent(focused::add);
        }
        return new ArrayList<>(javaScript);
    }

    /**
     * The element of every focus call queued so far, in order: the element parameter a script calls
     * {@code $N.focus(} on, as a component's {@code focus()} and {@code callJsFunction("focus")} do. A call
     * counts only when its element can take focus when the call is sent (visible, enabled, and natively
     * focusable or carrying a {@code tabindex}): the browser ignores focus on anything else. A call made on a
     * component that was detached before the response is dropped, as the browser never sees it, and a
     * script that focuses an element it looks up itself, such as the skip link's click handler, is no
     * focus call.
     */
    List<Element> focusTargets() {
        javaScriptCalls();
        return List.copyOf(focused);
    }

    // The element a call focuses: parameter N of its first "$N.focus(", when that element can take focus.
    private static Optional<Element> focusTarget(List<Object> call) {
        Matcher focusCall = FOCUS_CALL.matcher(String.valueOf(call.get(0)));
        if (!focusCall.find()) {
            return Optional.empty();
        }
        int index = Integer.parseInt(focusCall.group(1)) + 1;
        if (index >= call.size() || !(call.get(index) instanceof Element target)) {
            return Optional.empty();
        }
        return A11y.scriptFocusable(target) ? Optional.of(target) : Optional.empty();
    }

    /** {@code true} when something asked the browser to reload the page. */
    boolean pageReloadRequested() {
        return javaScriptCalls().stream()
            .anyMatch(call -> String.valueOf(call.get(0)).contains("location.reload"));
    }

    /**
     * Reloads the page as the browser does: a new UI, with none of the old one's toasts or dialogs, in
     * the same session. The caller builds the route's view again, as the router would.
     */
    void reloadPage() {
        ui = new RecordingUi();
        UI.setCurrent(ui);
        ensureUiSession();
        javaScript.clear();
        focused.clear();
    }

    /** The cell texts of column {@code header} of every grid table under {@code root}, top to bottom. */
    static List<String> gridColumn(Component root, String header) {
        List<String> cells = new ArrayList<>();
        collectColumn(root.getElement(), header, cells);
        return cells;
    }

    /** All text {@code root} shows: text nodes plus the text of every {@code innerHTML} property. */
    static String text(Component root) {
        StringBuilder text = new StringBuilder(root.getElement().getTextRecursively());
        appendInnerHtml(root.getElement(), text);
        return text.toString();
    }

    /** The page title of an {@code LkPage}. */
    static String pageTitle(Component page) {
        return find(page, H1.class).stream().map(H1::getText).findFirst().orElse("");
    }

    /** Every component of {@code type} under {@code root}, {@code root} included, in tree order. */
    static <T extends Component> List<T> find(Component root, Class<T> type) {
        return InboxViewHarness.find(root, type);
    }

    /** The first button (Vaadin's or a native one) under {@code root} whose text is {@code label}. */
    static Component button(Component root, String label) {
        return buttons(root, label).stream().findFirst()
            .orElseThrow(() -> new AssertionError("no button \"" + label + "\" in: " + text(root)));
    }

    /** Every button (Vaadin's or a native one) under {@code root} whose text is {@code label}. */
    static List<Component> buttons(Component root, String label) {
        List<Component> found = new ArrayList<>();
        for (Button b : find(root, Button.class)) {
            if (label.equals(b.getText().trim())) {
                found.add(b);
            }
        }
        for (NativeButton b : find(root, NativeButton.class)) {
            if (label.equals(b.getElement().getTextRecursively().trim())
                || label.equals(b.getElement().getAttribute("title"))) {
                found.add(b);
            }
        }
        return found;
    }

    /** The first component of {@code type} under {@code root} whose label is {@code label}. */
    static <T extends Component> T field(Component root, Class<T> type, String label) {
        return find(root, type).stream()
            .filter(f -> label.equals(f.getElement().getProperty("label")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " labelled \"" + label + "\""));
    }

    /**
     * Clicks {@code component} as the visitor would, which only a control that shows and is enabled allows.
     *
     * @throws AssertionError when {@code component} or an ancestor is hidden, or it is disabled
     */
    static void click(Component component) {
        A11y.requireActionable(component.getElement(), "click");
        forceClick(component);
    }

    /**
     * Fires a click on {@code component} whether or not it shows or is enabled, as a crafted request could:
     * only for a test of the server's own guard behind a control the browser would not let anyone click.
     */
    static void forceClick(Component component) {
        ComponentUtil.fireEvent(component, new ClickEvent<>(component));
    }

    /**
     * Fires a DOM click on {@code element}, for a listener added with {@code addEventListener}.
     *
     * @throws AssertionError when {@code element} or an ancestor is hidden, or it is disabled
     */
    static void domClick(Element element) {
        A11y.requireActionable(element, "click");
        element.getNode().getFeature(ElementListenerMap.class)
            .fireEvent(new DomEvent(element, "click", Json.createObject()));
    }

    /** {@code true} when {@code component} and every ancestor is visible. */
    static boolean shown(Component component) {
        for (Component c = component; c != null; c = c.getParent().orElse(null)) {
            if (!c.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        new CompanyManagementBridge(bridgedBefore);
        UI.setCurrent(null);
        VaadinService.setCurrent(null);
        MapBackedVaadinSession.uninstall();
    }

    // ---- internals ---------------------------------------------------------------------------

    // A dialog's footer is not among its component children, so walk the footer's own elements.
    private static List<Button> footerButtons(Dialog dialog) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialog.getFooter().getElement(), buttons);
        return buttons;
    }

    private static void collectButtons(Element element, List<Button> found) {
        element.getComponent().filter(Button.class::isInstance).map(Button.class::cast).ifPresent(found::add);
        element.getChildren().forEach(child -> collectButtons(child, found));
    }

    private static void collectColumn(Element element, String header, List<String> cells) {
        if (element.isTextNode()) {
            return;
        }
        if (!"table".equals(element.getTag())) {
            element.getChildren().forEach(child -> collectColumn(child, header, cells));
            return;
        }
        List<Element> sections = element.getChildren().toList();
        List<String> headers = sections.stream()
            .filter(section -> "thead".equals(section.getTag()))
            .flatMap(Element::getChildren)
            .flatMap(Element::getChildren)
            .map(Element::getTextRecursively)
            .toList();
        int column = headers.indexOf(header);
        if (column < 0) {
            return;
        }
        sections.stream()
            .filter(section -> "tbody".equals(section.getTag()))
            .flatMap(Element::getChildren)
            .forEach(row -> cells.add(row.getChild(column).getTextRecursively()));
    }

    private static void appendInnerHtml(Element element, StringBuilder text) {
        if (element.hasProperty("innerHTML")) {
            text.append(' ').append(TAG.matcher(element.getProperty("innerHTML", "")).replaceAll(" "));
        }
        element.getChildren().forEach(child -> appendInnerHtml(child, text));
    }

    private static String toastText(Notification toast) {
        return find(toast, Span.class).stream()
            .filter(span -> !(span instanceof LkIcon) && !span.getText().isBlank())
            .map(HasText::getText)
            .findFirst()
            .orElse("");
    }

    // RouterLink asks the current service's router for the URL of its target; every target gets one.
    private static VaadinService stubRouterService() {
        Answer<Object> urlForEveryTarget = call -> call.getMethod().getReturnType() == Optional.class
            && call.getMethod().getName().toLowerCase().contains("url")
            ? Optional.of("stub")
            : Mockito.RETURNS_DEFAULTS.answer(call);
        RouteRegistry registry = mock(RouteRegistry.class, urlForEveryTarget);
        Router router = mock(Router.class);
        when(router.getRegistry()).thenReturn(registry);
        VaadinService service = mock(VaadinService.class);
        when(service.getRouter()).thenReturn(router);
        // A component that reads the deployment mode when attached (Flow's Label does) finds development mode.
        when(service.getDeploymentConfiguration()).thenReturn(mock(DeploymentConfiguration.class));
        return service;
    }

    // Records instead of queueing in the state tree (see InboxViewHarness), and records navigations
    // instead of routing them, since this UI has no session and no router.
    private static final class RecordingUi extends UI {

        private final List<SerializableConsumer<ExecutionContext>> recorded = new ArrayList<>();
        private final List<String> navigations = new ArrayList<>();
        private final List<Runnable> onNavigation = new ArrayList<>();

        // The router builds the target, and runs its layout's afterNavigation, inside navigate(), before the
        // navigating code goes on; a listener stands in for that.
        private void record(String navigation) {
            navigations.add(navigation);
            onNavigation.forEach(Runnable::run);
        }

        @Override
        public StateTree.ExecutionRegistration beforeClientResponse(Component component,
                                                                    SerializableConsumer<ExecutionContext> execution) {
            recorded.add(execution);
            return () -> recorded.remove(execution);
        }

        void runRecorded() {
            while (!recorded.isEmpty()) {
                List<SerializableConsumer<ExecutionContext>> due = List.copyOf(recorded);
                recorded.clear();
                due.forEach(execution -> execution.accept(new ExecutionContext(this, false)));
            }
        }

        @Override
        public <T extends Component> Optional<T> navigate(Class<T> target) {
            record(target.getSimpleName());
            return Optional.empty();
        }

        @Override
        public <T extends Component> Optional<T> navigate(Class<T> target, RouteParameters parameters) {
            record(target.getSimpleName() + parameters);
            return Optional.empty();
        }

        @Override
        public <T extends Component> Optional<T> navigate(Class<? extends T> target, QueryParameters query) {
            record(target.getSimpleName() + "?" + query.getQueryString());
            return Optional.empty();
        }

        @Override
        public <C extends Component> Optional<C> navigate(Class<? extends C> target, RouteParameters parameters,
                                                          QueryParameters query) {
            record(target.getSimpleName() + parameters + "?" + query.getQueryString());
            return Optional.empty();
        }

        @Override
        public void navigate(String location) {
            record(location);
        }

        @Override
        public void navigate(String location, QueryParameters query) {
            record(location + "?" + query.getQueryString());
        }
    }
}
