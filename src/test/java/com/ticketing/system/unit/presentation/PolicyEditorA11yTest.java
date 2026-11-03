package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.company.PurchasePolicyEditorView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.dom.Element;

/**
 * The purchase-policy editor works from a keyboard and tells a screen reader what changed (#188, P-B1,
 * A-B3, P-M10, P-M4; WCAG 2.1.1, 4.1.2, 4.1.3):
 * <ul>
 *   <li>the policy tree's node bodies and +R / +G pills are keyboard buttons: {@code tabindex=0},
 *       {@code role=button}, an {@code aria-label}, and Enter or Space doing what a click does; the
 *       {@code svg} is a named {@code role=group}, so a keyboard user can build a policy;</li>
 *   <li>a load-error banner is an alert, the validity badge a status, and the Company / Event scope
 *       toggle says which scope is on ({@code aria-pressed}).</li>
 * </ul>
 *
 * <p>The tree is drawn by {@code policy-tree-map.js} with D3 in the browser, and the test classpath has no
 * JavaScript engine, so its contract is pinned on the script's text, as {@code HoldCountdownTest} does for
 * the countdown. A keyboard button may be made in place or by a helper function the drawing code calls.
 */
class PolicyEditorA11yTest {

    // ---- F13: policy editor keyboard path and announcements (#188) ----

    private static final Path TREE_SCRIPT = Path.of("src", "main", "frontend", "policy-tree-map.js");
    private static final String REASON = "reason-under-test";
    private static final String BADGE_DOT = "●";

    private static final Pattern ROLE_BUTTON = attr("role", "button");
    private static final Pattern ROLE_GROUP = attr("role", "group");
    private static final Pattern TABINDEX_ZERO =
        Pattern.compile("['\"]tabindex['\"]\\s*,\\s*['\"]?0['\"]?\\s*\\)|tabIndex\\s*=\\s*0\\b");
    private static final Pattern ARIA_LABEL = Pattern.compile("['\"]aria-label['\"]");
    private static final Pattern KEYDOWN = Pattern.compile("['\"]keydown['\"]");
    private static final Pattern ENTER = Pattern.compile("['\"]Enter['\"]");
    private static final Pattern SPACE = Pattern.compile("['\"] ['\"]|['\"]Space(bar)?['\"]");
    private static final Pattern NODE_ACTION = Pattern.compile("onNodeAction\\(");
    private static final Pattern SVG_STATEMENT =
        Pattern.compile("\\.append\\(\\s*['\"]svg['\"]\\s*\\)|(?<![\\w.])svg\\s*\\.\\s*(attr|attrs|setAttribute)\\b");
    private static final Pattern FUNCTION_DECLARATION =
        Pattern.compile("function\\s+(\\w+)\\s*\\(|(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:function\\b|\\([^)]*\\)\\s*=>)");

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- policy-tree-map.js ----

    @Test
    void GivenThePolicyTreeScript_WhenItDrawsTheTree_ThenTheSvgIsANamedGroup() throws IOException {
        String svg = svgStatements(treeScript());

        assertTrue(ROLE_GROUP.matcher(svg).find(), "the tree's svg must have role=group: " + svg);
        assertTrue(ARIA_LABEL.matcher(svg).find(), "the tree's svg must have an aria-label: " + svg);
    }

    @Test
    void GivenThePolicyTreeScript_WhenItDrawsANodeBody_ThenTheBodyIsAKeyboardButton() throws IOException {
        String script = treeScript();
        String nodeBodies = between(script, "window.renderPolicyTree", "function addPill");

        assertKeyboardButton(script, nodeBodies, "a node body");
    }

    @Test
    void GivenThePolicyTreeScript_WhenItDrawsAnAddPill_ThenThePillIsAKeyboardButton() throws IOException {
        String script = treeScript();
        String pills = between(script, "function addPill", null);

        assertKeyboardButton(script, pills, "a +R / +G pill");
    }

    // ---- PurchasePolicyEditorView ----

    @Test
    void GivenTheCompaniesFailToLoad_WhenTheEditorOpens_ThenTheErrorBannerIsAnAlert() {
        PurchasePolicyEditorPresenter presenter = presenter();
        doReturn(new PurchasePolicyEditorPresenter.ContextOutcome.Failure(REASON)).when(presenter).resolveContext(any());

        assertAlertSaying(open(presenter), REASON);
    }

    @Test
    void GivenTheEventsFailToLoad_WhenTheEditorOpens_ThenTheErrorBannerIsAnAlert() {
        PurchasePolicyEditorPresenter presenter = presenter();
        doReturn(new ListOutcome.Failure<>(REASON)).when(presenter).listEvents(any(), anyInt());

        assertAlertSaying(open(presenter), REASON);
    }

    @Test
    void GivenThePolicyFailsToLoad_WhenTheEditorOpens_ThenTheErrorBannerIsAnAlert() {
        PurchasePolicyEditorPresenter presenter = presenter();
        doReturn(new PurchasePolicyEditorPresenter.LoadOutcome.Failure(REASON))
            .when(presenter).load(any(), anyInt(), anyInt(), anyBoolean());

        assertAlertSaying(open(presenter), REASON);
    }

    @Test
    void GivenTheEditorOpens_WhenItShowsTheValidity_ThenTheBadgeIsAStatus() {
        PurchasePolicyEditorView view = open(presenter());

        // The badge reads "● Valid" or "● <why not>"; the deepest element holding it all is the badge.
        Element badge = A11y.elements(view, e -> {
            String raw = e.getTextRecursively().trim();
            return raw.startsWith(BADGE_DOT) && raw.length() > BADGE_DOT.length();
        }).stream()
            .reduce((outer, inner) -> inner)
            .orElseThrow(() -> new AssertionError("the editor shows no validity badge"));
        assertTrue(hasRoleAround(badge, "status"),
            "the validity badge must be a status (role=status), so a change is announced: " + A11y.describe(badge));
    }

    @Test
    void GivenTheCompanyScope_WhenTheOwnerSwitchesToEventScope_ThenTheToggleSaysWhichScopeIsPressed() {
        PurchasePolicyEditorView view = open(presenter());
        assertEquals(List.of("true", "false"), pressed(view), "Company-level is on: " + scopeButtons(view));

        ViewHarness.click(ViewHarness.button(view, "Event-level"));

        assertEquals(List.of("false", "true"), pressed(view), "Event-level is on: " + scopeButtons(view));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Pattern attr(String name, String value) {
        return Pattern.compile("['\"]" + name + "['\"]\\s*,\\s*['\"]" + value + "['\"]");
    }

    private static String treeScript() throws IOException {
        return Files.readString(TREE_SCRIPT, StandardCharsets.UTF_8);
    }

    // The statement that creates the svg (append('svg') up to its ';'), and every statement on `svg.`.
    private static String svgStatements(String script) {
        StringBuilder svg = new StringBuilder();
        Matcher m = SVG_STATEMENT.matcher(script);
        while (m.find()) {
            int end = script.indexOf(';', m.start());
            svg.append(script, m.start(), end < 0 ? script.length() : end).append(";\n");
        }
        assertTrue(svg.length() > 0, "the script creates no svg");
        return svg.toString();
    }

    private static String between(String script, String start, String end) {
        int from = script.indexOf(start);
        assertTrue(from >= 0, "the script has no \"" + start + "\"");
        int to = end == null ? script.length() : script.indexOf(end, from);
        return script.substring(from, to < 0 ? script.length() : to);
    }

    // The drawing code makes its element a keyboard button itself, or calls a helper that does.
    private static void assertKeyboardButton(String script, String drawing, String what) {
        List<String> helpers = keyboardButtonHelpers(script);
        boolean viaHelper = helpers.stream().anyMatch(name -> Pattern.compile("\\b" + name + "\\s*\\(").matcher(drawing).find()
            && !drawing.contains("function " + name));
        String code = viaHelper ? script : drawing;
        List<String> missing = new ArrayList<>();
        check(code, ROLE_BUTTON, "role=button", missing);
        check(code, TABINDEX_ZERO, "tabindex=0", missing);
        check(code, ARIA_LABEL, "an aria-label", missing);
        check(code, KEYDOWN, "a keydown listener", missing);
        check(code, ENTER, "Enter", missing);
        check(code, SPACE, "Space", missing);
        check(code, NODE_ACTION, "the click's server action", missing);
        assertTrue(missing.isEmpty(), what + " must be a keyboard button; the script gives it no " + missing);
    }

    private static void check(String code, Pattern pattern, String what, List<String> missing) {
        if (!pattern.matcher(code).find()) {
            missing.add(what);
        }
    }

    // Named functions whose body sets role=button and listens for keydown.
    private static List<String> keyboardButtonHelpers(String script) {
        List<String> helpers = new ArrayList<>();
        Matcher m = FUNCTION_DECLARATION.matcher(script);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            int open = script.indexOf('{', m.end());
            if (open < 0) {
                continue;
            }
            String body = script.substring(open, closingBrace(script, open));
            if (ROLE_BUTTON.matcher(body).find() && KEYDOWN.matcher(body).find()) {
                helpers.add(name);
            }
        }
        return helpers;
    }

    private static int closingBrace(String script, int open) {
        int depth = 0;
        for (int i = open; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i + 1;
            }
        }
        return script.length();
    }

    // The real presenter builds and validates the tree; only its service-backed reads are stubbed.
    private static PurchasePolicyEditorPresenter presenter() {
        PurchasePolicyEditorPresenter presenter = spy(new PurchasePolicyEditorPresenter(
            mock(CompanyManagementService.class), mock(EventManagementService.class), mock(SessionIdentity.class)));
        doReturn(new PurchasePolicyEditorPresenter.Identity(ViewHarness.MEMBER_TOKEN)).when(presenter).resolveIdentity();
        doReturn(new PurchasePolicyEditorPresenter.ContextOutcome.Ready(List.of(new MyCompanyDTO(3, "Acme", "Founder"))))
            .when(presenter).resolveContext(any());
        doReturn(new ListOutcome.Loaded<>(List.of())).when(presenter).listEvents(any(), anyInt());
        doReturn(new PurchasePolicyEditorPresenter.LoadOutcome.Success(presenter.emptyPolicy()))
            .when(presenter).load(any(), anyInt(), anyInt(), anyBoolean());
        return presenter;
    }

    private static PurchasePolicyEditorView open(PurchasePolicyEditorPresenter presenter) {
        PurchasePolicyEditorView view = new PurchasePolicyEditorView(presenter);
        view.beforeEnter(routeTo());
        return view;
    }

    private static void assertAlertSaying(Component view, String reason) {
        LkBanner banner = ViewHarness.find(view, LkBanner.class).stream()
            .filter(b -> b.getElement().getTextRecursively().contains(reason))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no banner says \"" + reason + "\""));
        assertTrue(hasRoleAround(banner.getElement(), "alert"),
            "a load-error banner must be an alert (role=alert, as Lk.asAlert sets): " + A11y.describe(banner.getElement()));
    }

    // The element carries the role, or sits in a region that does.
    private static boolean hasRoleAround(Element element, String role) {
        for (Element a = element; a != null; a = a.getParent()) {
            if (role.equals(a.getAttribute("role"))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> pressed(Component view) {
        return List.of("Company-level", "Event-level").stream()
            .map(label -> String.valueOf(ViewHarness.button(view, label).getElement().getAttribute("aria-pressed")))
            .toList();
    }

    private static List<String> scopeButtons(Component view) {
        return List.of("Company-level", "Event-level").stream()
            .map(label -> A11y.describe(ViewHarness.button(view, label).getElement()))
            .toList();
    }
}
