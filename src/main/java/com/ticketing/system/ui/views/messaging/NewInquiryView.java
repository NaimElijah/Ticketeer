package com.ticketing.system.ui.views.messaging;

import com.ticketing.system.shared.dto.CompanySummaryDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkChip;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkFieldRules;
import com.ticketing.system.ui.components.kit.LkFormErrors;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRemoveButton;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.messaging.NewInquiryPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.List;

/**
 * Member "New inquiry" composer (II.3.10). The member picks a production company (searchable, or
 * pre-filled via {@code ?company=<id>} from an event page's "Ask the Organizer" button),
 * writes a title + description, and submits. The inquiry opens a two-way chat that the company's
 * eligible role-holders answer; replies surface in the member's Support inbox. Backed by
 * {@link NewInquiryPresenter} → {@code MessagingService}.
 *
 * <p>Served at {@code /support/inquiry/new}.
 */
@Route(value = "support/inquiry/new", layout = MainLayout.class)
@PageTitle("New Inquiry · TicketHub")
@PermitAll
@RequireCapability(Capability.MEMBER_ACCOUNT)
public class NewInquiryView extends LkPage implements BeforeEnterObserver {

    private static final String CHOOSE_COMPANY = "Please choose a production company.";
    private static final String CHOSEN_COMPANY_LABEL = "Company:";
    private static final String ENTER_TITLE = "Enter a title.";
    private static final String ENTER_DESCRIPTION = "Enter a description.";

    private final NewInquiryPresenter presenter;
    private final LkFormErrors errors = new LkFormErrors();

    private final TextField companySearch = new TextField("Production company");
    private final Div companyResults = new Div();
    private final Div selectedCompanyBox = new Div();
    private final TextField subject = new TextField("Title");
    private final TextArea details = new TextArea("Description");

    private Integer selectedCompanyId;
    private String selectedCompanyName;

    /**
     * Builds the inquiry form; a {@code ?company=} query parameter is applied when the route is entered.
     */
    public NewInquiryView(NewInquiryPresenter presenter) {
        this.presenter = presenter;
        title("New Inquiry");
        subtitle("Ask a production company a question — their team replies in your Support inbox.");
        add(buildForm());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        List<String> values = event.getLocation().getQueryParameters()
            .getParameters().getOrDefault("company", List.of());
        if (!values.isEmpty()) {
            try {
                preselectCompany(Integer.parseInt(values.get(0)));
            } catch (NumberFormatException ignored) {
                // bad ?company= value — fall back to the manual picker
            }
        }
    }

    private Component buildForm() {
        Div narrow = new Div();
        narrow.addClassName("form-narrow");
        LkCard card = new LkCard("New Inquiry").pad(20);

        companySearch.setPlaceholder("Search production companies…");
        companySearch.setRequiredIndicatorVisible(true);
        // The box holds a search, not the answer: the chosen company is held apart and the box is cleared
        // once one is picked, so its own required check would flag a valid form. This view checks it.
        companySearch.setManualValidation(true);
        // A refusal still hands the box back to that required check on its next edit (LkFormErrors), and an
        // empty box there means no company is picked yet, so the check names the choice (#189).
        LkFieldRules.sayWhenEmpty(companySearch, CHOOSE_COMPANY);
        companySearch.setWidthFull();
        companySearch.setClearButtonVisible(true);
        companySearch.setValueChangeMode(ValueChangeMode.LAZY);
        companySearch.setValueChangeTimeout(200);
        companySearch.setPrefixComponent(new LkIcon("search", 16));
        companySearch.addValueChangeListener(e -> doSearch(e.getValue()));
        companyResults.addClassName("lk-search-results");
        selectedCompanyBox.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "6px");

        subject.setPlaceholder("Brief summary of your question");
        subject.setRequired(true);
        LkFieldRules.sayWhenEmpty(subject, ENTER_TITLE);
        LkTextLimit.apply(subject);
        subject.setWidthFull();
        details.setPlaceholder("Write your question to the organizer…");
        details.setMinHeight("160px");
        details.setRequired(true);
        LkFieldRules.sayWhenEmpty(details, ENTER_DESCRIPTION);
        LkTextLimit.apply(details);
        details.setWidthFull();

        LkCol col = new LkCol().gap(14);
        col.add(companySearch, companyResults, selectedCompanyBox, subject, details);
        card.add(col);

        LkRow actions = new LkRow().gap(8).justify("flex-end");
        actions.getStyle().set("margin-top", "16px");
        actions.add(
            new LkBtn("Cancel").variant(LkBtn.Variant.tertiary)
                .onClick(e -> UI.getCurrent().navigate(SupportInboxView.class)),
            new LkBtn("Submit Inquiry").variant(LkBtn.Variant.primary)
                .onClick(e -> submit()));
        card.add(actions);

        narrow.add(card);
        return narrow;
    }

    private void doSearch(String query) {
        companyResults.removeAll();
        if (query == null || query.isBlank()) {
            return;
        }
        switch (presenter.searchCompanies(query)) {
            case NewInquiryPresenter.SearchOutcome.Success ok -> renderResults(ok.companies());
            case NewInquiryPresenter.SearchOutcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not search companies", fail.reason()));
        }
    }

    private void renderResults(List<CompanySummaryDTO> companies) {
        companyResults.removeAll();
        for (CompanySummaryDTO c : companies) {
            NativeButton row = new NativeButton(c.name());
            row.addClassName("lk-search-res");
            row.addClickListener(e -> selectCompany(c.companyId(), c.name()));
            companyResults.add(row);
        }
    }

    private void preselectCompany(int companyId) {
        switch (presenter.searchCompanies("")) {
            case NewInquiryPresenter.SearchOutcome.Success ok -> ok.companies().stream()
                .filter(c -> c.companyId() == companyId)
                .findFirst()
                .ifPresent(c -> selectCompany(c.companyId(), c.name()));
            case NewInquiryPresenter.SearchOutcome.Failure ignored -> {
                // leave the picker empty
            }
        }
    }

    private void selectCompany(int id, String name) {
        this.selectedCompanyId = id;
        this.selectedCompanyName = name;
        companySearch.clear();
        companySearch.setInvalid(false);
        companyResults.removeAll();
        renderSelected();
    }

    private void renderSelected() {
        selectedCompanyBox.removeAll();
        if (selectedCompanyId != null) {
            // Labelled as the company the inquiry goes to, and removable, rather than a bare chip (#188,
            // WCAG 1.3.1, 2.1.1).
            LkChip chip = new LkChip(selectedCompanyName).active();
            chip.add(new LkRemoveButton(selectedCompanyName, this::clearCompany));
            selectedCompanyBox.add(Lk.muted(CHOSEN_COMPANY_LABEL), chip);
        }
    }

    // The chip's remove button is gone with it, so focus goes back to the search to pick another.
    private void clearCompany() {
        selectedCompanyId = null;
        selectedCompanyName = null;
        renderSelected();
        companySearch.focus();
    }

    private void submit() {
        errors.clear();
        if (selectedCompanyId == null) {
            errors.reject(companySearch, CHOOSE_COMPANY);
            refuse(CHOOSE_COMPANY);
            return;
        }
        if (subject.isEmpty()) {
            errors.reject(subject, ENTER_TITLE);
        }
        if (details.isEmpty()) {
            errors.reject(details, ENTER_DESCRIPTION);
        }
        if (!errors.isEmpty()) {
            refuse("Please fill in a title and description.");
            return;
        }
        switch (presenter.submit(AuthSession.token(), selectedCompanyId,
                subject.getValue(), details.getValue())) {
            case NewInquiryPresenter.ActionOutcome.Success ok -> {
                Toasts.success("Inquiry sent — replies will appear in your Support inbox.");
                UI.getCurrent().navigate(SupportInboxView.class,
                    QueryParameters.of("c", ok.conversationId()));
            }
            case NewInquiryPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case NewInquiryPresenter.ActionOutcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not submit the inquiry", fail.reason()));
        }
    }

    // The toast stays, but the refused fields now carry the message too, and the first takes focus (#188).
    private void refuse(String toast) {
        Toasts.failure(toast);
        errors.focusFirst();
    }
}
