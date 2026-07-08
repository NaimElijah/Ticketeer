package com.ticketing.system.ui.views.company;

import java.util.List;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.policy.PolicyTreeMap;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.ContextOutcome;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Group;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.LoadOutcome;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Node;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Op;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Rule;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.RuleType;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.SaveOutcome;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter.Validity;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;

import jakarta.annotation.security.PermitAll;

@Route(value = "owner/policies/:companyId?/:eventId?", layout = WorkspaceLayout.class)
@PageTitle("Purchase policies · TicketHub")
@PermitAll
@RequireCapability(Capability.EDIT_PURCHASE_POLICIES)
public class PurchasePolicyEditorView extends LkPage implements BeforeEnterObserver {

    private final PurchasePolicyEditorPresenter presenter;

    private Group   root;
    private String  memberToken;
    private int     companyId    = -1;
    private int     eventId      = -1;
    private boolean isEventLevel = false;          // default: company-level (fixes the old mislabel)

    private Integer routeCompanyId;                // optional deep-link from the URL
    private Integer routeEventId;

    private Span                          plainEnglishText;
    private PolicyTreeMap                 mapPanel;
    private Span                          validityBadge;
    private Span                          scopeContextLabel;
    private final Div                     loadErrorSlot = new Div();
    private Select<MyCompanyDTO>          companySelect;
    private Select<EventDetailDTO>        eventSelect;
    private NativeButton                  companyBtn;
    private NativeButton                  eventBtn;

    public PurchasePolicyEditorView(PurchasePolicyEditorPresenter presenter) {
        this.presenter = presenter;
        this.root = presenter.emptyPolicy();

        // Build the static UI exactly once, in the constructor — beforeEnter() only loads data.
        title("Purchase policies");
        subtitle("Pick a company (and optionally an event), then build the rules · click a node to edit · drag to pan · scroll to zoom.");

        add(buildScopeToolbar());
        add(loadErrorSlot);
        add(buildPlainEnglishBanner());
        add(buildCanvas());
        add(buildActionBar());
        add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18),
            "Validation rejects empty groups and non-numeric values before a policy can be saved."));
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        RouteParameters params = event.getRouteParameters();
        this.routeCompanyId = parseOpt(params.get("companyId").orElse(null));
        this.routeEventId   = parseOpt(params.get("eventId").orElse(null));

        this.memberToken = presenter.resolveIdentity().memberToken();

        initContext();
    }

    // ---------------------------------------------------------------------
    // Context: resolve the owner's companies, then drive the selectors
    // ---------------------------------------------------------------------

    private void initContext() {
        loadErrorSlot.removeAll();
        switch (presenter.resolveContext(memberToken)) {
            case ContextOutcome.NotAuthenticated na ->
                disableEditing("Please sign in again to edit policies.");
            case ContextOutcome.NoCompany nc ->
                disableEditing("You're not part of a company workspace yet — join or register one to set purchase policies.");
            case ContextOutcome.Failure f ->
                showLoadError(Lk.withReason("Could not load your companies", f.reason()), this::initContext);
            case ContextOutcome.Ready ready ->
                populateCompanies(ready.companies());
        }
    }

    private void populateCompanies(List<MyCompanyDTO> companies) {
        companySelect.setEnabled(true);
        companySelect.setItems(companies);

        MyCompanyDTO chosen = companies.get(0);
        if (routeCompanyId != null) {
            for (MyCompanyDTO c : companies) {
                if (c.companyId() == routeCompanyId) { chosen = c; break; }
            }
        }
        companySelect.setValue(chosen);   // fires onCompanyChosen()
    }

    private void onCompanyChosen(MyCompanyDTO c) {
        if (c == null) return;
        this.companyId = c.companyId();

        List<EventDetailDTO> events = presenter.listEvents(memberToken, companyId);
        eventSelect.setItems(events);

        EventDetailDTO preselect = null;
        if (routeEventId != null) {
            for (EventDetailDTO ev : events) {
                if (parseId(ev.eventId()) == routeEventId) { preselect = ev; break; }
            }
            routeEventId = null;          // consume the deep-link once
        }

        if (preselect != null) {
            isEventLevel = true;
            setScopeButtons();
            eventSelect.setEnabled(true);
            eventSelect.setValue(preselect);   // fires onEventChosen() → loads the event policy
        } else {
            isEventLevel = false;
            setScopeButtons();
            eventSelect.setEnabled(false);
            eventSelect.clear();
            this.eventId = -1;
            loadExistingPolicy();
            rebuildTree();
        }
    }

    private void onEventChosen(EventDetailDTO ev) {
        if (ev == null) return;
        this.eventId = parseId(ev.eventId());
        if (isEventLevel) {
            loadExistingPolicy();
            rebuildTree();
        }
    }

    private void disableEditing(String message) {
        companySelect.setItems(List.of());
        companySelect.setEnabled(false);
        eventSelect.setItems(List.of());
        eventSelect.setEnabled(false);
        this.companyId = -1;
        this.eventId = -1;
        this.root = presenter.emptyPolicy();
        loadErrorSlot.add(new LkBanner(LkBanner.Tone.error, new LkIcon("alert-circle", 16), message));
        rebuildTree();
    }

    private void loadExistingPolicy() {
        loadErrorSlot.removeAll();
        if (companyId <= 0 || (isEventLevel && eventId <= 0)) {
            this.root = presenter.emptyPolicy();
            return;
        }
        switch (presenter.load(memberToken, companyId, eventId, isEventLevel)) {
            case LoadOutcome.Success s -> this.root = s.root();
            case LoadOutcome.NotAuthenticated na -> {
                this.root = presenter.emptyPolicy();
                Toasts.warn("Your session has expired — please sign in again.");
            }
            case LoadOutcome.Failure f -> {
                this.root = presenter.emptyPolicy();
                showLoadError(Lk.withReason("Could not load existing policy", f.reason()),
                    () -> { loadExistingPolicy(); rebuildTree(); });
            }
        }
    }

    private void showLoadError(String message, Runnable retry) {
        LkBanner err = new LkBanner(LkBanner.Tone.error, new LkIcon("alert-circle", 16), message);
        LkBtn retryBtn = new LkBtn("Retry").variant(LkBtn.Variant.secondary);
        retryBtn.addClickListener(ev -> retry.run());
        err.setAction(retryBtn);
        loadErrorSlot.add(err);
    }

    // ---------------------------------------------------------------------
    // Scope toolbar (company picker · scope toggle · event picker · status)
    // ---------------------------------------------------------------------

    private Component buildScopeToolbar() {
        Div bar = new Div(); bar.addClassName("pe-toolbar");

        Div scope = new Div(); scope.addClassName("pe-scope");

        companySelect = new Select<>();
        companySelect.setLabel("Company");
        companySelect.setItemLabelGenerator(MyCompanyDTO::name);
        companySelect.setWidth("220px");
        companySelect.setEnabled(false);
        companySelect.addValueChangeListener(e -> onCompanyChosen(e.getValue()));
        scope.add(companySelect);

        Span lbl = new Span("Scope"); lbl.addClassName("pe-scope-label");
        scope.add(lbl);

        Div seg = new Div(); seg.addClassName("pe-seg");
        companyBtn = new NativeButton("Company-level"); companyBtn.addClassName("pe-seg-opt");
        eventBtn   = new NativeButton("Event-level");   eventBtn.addClassName("pe-seg-opt");
        setScopeButtons();
        companyBtn.addClickListener(e -> selectCompanyScope());
        eventBtn.addClickListener(e -> selectEventScope());
        seg.add(companyBtn, eventBtn);
        scope.add(seg);

        eventSelect = new Select<>();
        eventSelect.setLabel("Event");
        eventSelect.setItemLabelGenerator(EventDetailDTO::name);
        eventSelect.setPlaceholder("Choose an event");
        eventSelect.setWidth("220px");
        eventSelect.setEnabled(false);
        eventSelect.addValueChangeListener(e -> onEventChosen(e.getValue()));
        scope.add(eventSelect);

        Div ctx = new Div(); ctx.addClassName("pe-scope-ctx");
        ctx.add(new LkIcon("ticket", 16));
        scopeContextLabel = new Span();
        ctx.add(scopeContextLabel);
        scope.add(ctx);

        Div status = new Div(); status.addClassName("pe-status");
        validityBadge = new Span(); validityBadge.addClassName("pe-valid");
        status.add(validityBadge);

        bar.add(scope, status);
        return bar;
    }

    private void selectCompanyScope() {
        isEventLevel = false;
        setScopeButtons();
        eventSelect.setEnabled(false);
        loadExistingPolicy();
        rebuildTree();
    }

    private void selectEventScope() {
        isEventLevel = true;
        setScopeButtons();
        eventSelect.setEnabled(true);
        if (eventId > 0) loadExistingPolicy();
        else             this.root = presenter.emptyPolicy();
        rebuildTree();
    }

    private void setScopeButtons() {
        if (isEventLevel) { eventBtn.addClassName("on"); companyBtn.removeClassName("on"); }
        else              { companyBtn.addClassName("on"); eventBtn.removeClassName("on"); }
    }

    private void refreshScope() {
        if (scopeContextLabel == null) return;
        if (isEventLevel) {
            scopeContextLabel.setText(eventId > 0
                ? "Event: " + currentEventName()
                : "Event-level — choose an event");
        } else {
            scopeContextLabel.setText(companyId > 0
                ? "Company: " + currentCompanyName()
                : "No company selected");
        }
    }

    private String currentCompanyName() {
        MyCompanyDTO c = companySelect.getValue();
        return c != null ? c.name() : ("#" + companyId);
    }

    private String currentEventName() {
        EventDetailDTO ev = eventSelect.getValue();
        return ev != null ? ev.name() : ("#" + eventId);
    }

    private void updateValidity() {
        if (validityBadge == null) return;
        Validity v = presenter.validate(root);
        validityBadge.removeAll();
        validityBadge.add(new Span("● "), new Span(v.valid() ? "Valid" : v.message()));
        validityBadge.getStyle()
            .set("color", v.valid() ? "#16a34a" : "#dc2626")
            .set("font-size", "12.5px")
            .set("font-weight", "600");
    }

    // ---------------------------------------------------------------------
    // Plain-English banner
    // ---------------------------------------------------------------------

    private Component buildPlainEnglishBanner() {
        Div plain = new Div(); plain.addClassName("pe-plain");
        Span emoji = new Span(); emoji.addClassName("pe-plain-emoji"); emoji.add(new LkIcon("comment", 22));
        Div text = new Div();
        Span kicker = new Span("In plain English"); kicker.addClassName("pe-plain-k");
        plainEnglishText = new Span(); plainEnglishText.addClassName("pe-plain-text");
        text.add(kicker, plainEnglishText);
        plain.add(emoji, text);
        return plain;
    }

    // ---------------------------------------------------------------------
    // Canvas
    // ---------------------------------------------------------------------

    private Component buildCanvas() {
        mapPanel = new PolicyTreeMap();
        mapPanel.setOnAction(this::handleNodeAction);
        return mapPanel;
    }

    /** Re-render every view surface from the current model (all data comes from the presenter). */
    private void rebuildTree() {
        mapPanel.render(presenter.toTreeJson(root));
        plainEnglishText.getElement().setProperty("innerHTML", presenter.toPlainEnglishHtml(root));
        refreshScope();
        updateValidity();
    }

    private void handleNodeAction(String nodeId, String action) {
        Node n = presenter.findNode(root, nodeId);
        if (n == null) return;
        switch (action) {
            case "click" -> {
                if (n instanceof Rule r) openRuleDialog(r);
                else if (n instanceof Group g) openGroupDialog(g);
            }
            case "addRule" -> {
                presenter.addRule(root, nodeId);
                rebuildTree();
                Toasts.success("Rule added — click it to edit.");
            }
            case "addGroup" -> {
                presenter.addGroup(root, nodeId);
                rebuildTree();
                Toasts.success("Group added — click it to add children.");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Rule editor dialog
    // ---------------------------------------------------------------------

    private void openRuleDialog(Rule r) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Edit rule");
        d.setWidth("min(420px, 100vw - 32px)");

        Select<RuleType> typeSelect = new Select<>();
        typeSelect.setLabel("Rule type");
        typeSelect.setItems(RuleType.values());
        typeSelect.setItemLabelGenerator(Enum::name);
        typeSelect.setValue(r.type());
        typeSelect.setWidthFull();

        TextField valueField = new TextField("Value");
        valueField.setValue(r.value() == null ? "" : r.value());
        valueField.setWidthFull();
        applyValueHelper(valueField, typeSelect.getValue());
        typeSelect.addValueChangeListener(e -> applyValueHelper(valueField, e.getValue()));

        Div body = new Div(typeSelect, valueField);
        body.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "12px");
        d.add(body);

        if (presenter.canDelete(root, r.id())) {
            Button delete = new Button("Delete rule", e -> {
                presenter.deleteNode(root, r.id());
                d.close(); rebuildTree(); Toasts.warn("Rule removed.");
            });
            delete.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            delete.getStyle().set("margin-right", "auto");
            d.getFooter().add(delete);
        }

        Button cancel = new Button("Cancel", e -> d.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button save = new Button("Save", e -> {
            presenter.updateRule(root, r.id(), typeSelect.getValue(), valueField.getValue());
            d.close(); rebuildTree(); Toasts.success("Rule updated.");
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(cancel, save);
        d.open();
    }

    private void applyValueHelper(TextField field, RuleType t) {
        field.setHelperText("Numeric value · unit = " + t.unit());
    }

    // ---------------------------------------------------------------------
    // Group editor dialog
    // ---------------------------------------------------------------------

    private void openGroupDialog(Group g) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Edit group");
        d.setWidth("min(440px, 100vw - 32px)");

        RadioButtonGroup<Op> opGroup = new RadioButtonGroup<>();
        opGroup.setLabel("Operator");
        opGroup.setItems(Op.AND, Op.OR);
        opGroup.setItemLabelGenerator(op -> switch (op) {
            case AND -> "AND · all children must be true";
            case OR  -> "OR · any child can be true";
        });
        opGroup.setValue(g.op());

        Button addRule = new Button("+ Add rule", e -> {
            presenter.addRule(root, g.id());
            d.close(); rebuildTree(); Toasts.success("Rule added — click it to edit.");
        });
        addRule.addThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_TERTIARY);

        Button addGroup = new Button("+ Add group", e -> {
            presenter.addGroup(root, g.id());
            d.close(); rebuildTree(); Toasts.success("Group added — click it to add children.");
        });
        addGroup.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Div addRow = new Div(addRule, addGroup);
        addRow.getStyle().set("display", "flex").set("gap", "8px").set("margin-top", "4px");

        Div body = new Div(opGroup, addRow);
        body.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "12px");
        d.add(body);

        if (presenter.canDelete(root, g.id())) {
            Button delete = new Button("Delete group", e -> {
                presenter.deleteNode(root, g.id());
                d.close(); rebuildTree(); Toasts.warn("Group removed.");
            });
            delete.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            delete.getStyle().set("margin-right", "auto");
            d.getFooter().add(delete);
        }

        Button cancel = new Button("Cancel", e -> d.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button save = new Button("Save", e -> {
            presenter.updateGroupOp(root, g.id(), opGroup.getValue());
            d.close(); rebuildTree(); Toasts.success("Group updated.");
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        d.getFooter().add(cancel, save);
        d.open();
    }

    // ---------------------------------------------------------------------
    // Action bar + save
    // ---------------------------------------------------------------------

    private Component buildActionBar() {
        Div bar = new Div(); bar.addClassName("pe-actionbar");

        Div left = new Div(); left.addClassName("l");
        left.add(new LkBtn("Discard changes").variant(LkBtn.Variant.tertiary)
            .onClick(e -> { loadExistingPolicy(); rebuildTree(); Toasts.warn("Changes discarded."); }));

        Div right = new Div(); right.addClassName("r");
        right.add(new LkBtn("Save policy").variant(LkBtn.Variant.primary)
            .onClick(e -> savePolicy()));

        bar.add(left, right);
        return bar;
    }

    private void savePolicy() {
        if (companyId <= 0) { Toasts.failure("Choose a company first."); return; }
        if (isEventLevel && eventId <= 0) { Toasts.failure("Choose an event, or switch to Company-level."); return; }
        switch (presenter.save(memberToken, companyId, eventId, isEventLevel, root)) {
            case SaveOutcome.Success s           -> Toasts.success("Policy saved.");
            case SaveOutcome.NotAuthenticated na  -> Toasts.failure("Your session has expired — please sign in again.");
            case SaveOutcome.Invalid inv         -> Toasts.warn(inv.reason());
            case SaveOutcome.Failure f           -> Toasts.failure("Couldn't save the policy — please try again.");
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Integer parseOpt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static int parseId(String s) {
        if (s == null) return -1;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }
}