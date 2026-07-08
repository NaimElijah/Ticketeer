package com.ticketing.system.Presentation.views.messaging;
import com.ticketing.system.messaging.application.service.MessagingService;

import com.ticketing.system.Core.Application.dto.CompanySummaryDTO;
import com.ticketing.system.Presentation.components.Toasts;
import com.ticketing.system.Presentation.components.kit.LkBtn;
import com.ticketing.system.Presentation.components.kit.LkCard;
import com.ticketing.system.Presentation.components.kit.LkChip;
import com.ticketing.system.Presentation.components.kit.LkCol;
import com.ticketing.system.Presentation.components.kit.LkIcon;
import com.ticketing.system.Presentation.components.kit.LkPage;
import com.ticketing.system.Presentation.components.kit.LkRow;
import com.ticketing.system.Presentation.layouts.MainLayout;
import com.ticketing.system.Presentation.presenters.messaging.NewInquiryPresenter;
import com.ticketing.system.Presentation.session.AuthSession;
import com.ticketing.system.Presentation.views.account.SupportInboxView;
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
 * pre-filled via {@code ?company=<id>} from an event/company page's "Ask the organizer" button),
 * writes a title + description, and submits. The inquiry opens a two-way chat that the company's
 * eligible role-holders answer; replies surface in the member's Support inbox. Backed by
 * {@link NewInquiryPresenter} → {@code MessagingService}.
 */
@Route(value = "support/inquiry/new", layout = MainLayout.class)
@PageTitle("New Inquiry · TicketHub")
@PermitAll
public class NewInquiryView extends LkPage implements BeforeEnterObserver {

    private final NewInquiryPresenter presenter;

    private final TextField companySearch = new TextField("Production company");
    private final Div companyResults = new Div();
    private final Div selectedCompanyBox = new Div();
    private final TextField subject = new TextField("Title");
    private final TextArea details = new TextArea("Description");

    private Integer selectedCompanyId;
    private String selectedCompanyName;

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
        subject.setWidthFull();
        details.setPlaceholder("Write your question to the organizer…");
        details.setMinHeight("160px");
        details.setRequired(true);
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
                Toasts.failure("Could not search companies — please try again.");
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
        companyResults.removeAll();
        renderSelected();
    }

    private void renderSelected() {
        selectedCompanyBox.removeAll();
        if (selectedCompanyId != null) {
            selectedCompanyBox.add(new LkChip(selectedCompanyName).active());
        }
    }

    private void submit() {
        if (selectedCompanyId == null) {
            Toasts.failure("Please choose a production company.");
            return;
        }
        if (subject.isEmpty() || details.isEmpty()) {
            Toasts.failure("Please fill in a title and description.");
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
                Toasts.failure("Could not submit the inquiry — please try again.");
        }
    }
}
