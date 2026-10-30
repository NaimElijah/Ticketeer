package com.ticketing.system.ui.views.messaging;

import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.messaging.SubmitComplaintPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.List;

@Route(value = "support/new", layout = MainLayout.class)
@PageTitle("Submit Complaint · TicketHub")
@PermitAll
public class SubmitComplaintView extends LkPage {

    private final SubmitComplaintPresenter presenter;

    private final LkSelect about    = new LkSelect("An order or ticket",
        List.of("An order or ticket", "An event or organizer", "Payment or refund", "The TicketHub app", "Something else"));
    private final TextField subject = new TextField("Subject");
    private final TextArea  details = new TextArea("Details");

    public SubmitComplaintView(SubmitComplaintPresenter presenter) {
        this.presenter = presenter;

        title("Submit a Complaint");
        subtitle("Tell us what went wrong — an admin will respond shortly.");
        add(buildForm());
    }

    private Component buildForm() {
        Div narrow = new Div();
        narrow.addClassName("form-narrow");

        LkCard card = new LkCard("New Complaint").pad(20);

        subject.setPlaceholder("Brief summary of the issue");
        subject.setRequired(true);
        LkTextLimit.apply(subject);
        subject.setWidthFull();
        details.setPlaceholder("Describe what happened, including any order or event references…");
        details.setMinHeight("160px");
        details.setRequired(true);
        LkTextLimit detailsLimit = LkTextLimit.apply(details, presenter.maxDetailsLength(about.getValue()));
        details.setWidthFull();
        about.label("About");
        // The category is stored with the details, so a longer one leaves them less room: flag text
        // already typed past the new limit at once.
        about.onChange(value -> detailsLimit.setMax(presenter.maxDetailsLength(value)));

        LkCol col = new LkCol().gap(14);
        col.add(about, subject, details);
        col.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 17),
            "Complaints go to the TicketHub admin team and are tracked in your Support inbox."));
        card.add(col);

        LkRow actions = new LkRow().gap(8).justify("flex-end");
        actions.getStyle().set("margin-top", "16px");
        actions.add(
            new LkBtn("Cancel").variant(LkBtn.Variant.tertiary)
                .onClick(e -> UI.getCurrent().navigate(SupportInboxView.class)),
            new LkBtn("Submit Complaint").variant(LkBtn.Variant.primary)
                .onClick(e -> submit())
        );
        card.add(actions);

        narrow.add(card);
        return narrow;
    }

    private void submit() {
        if (subject.isEmpty() || details.isEmpty()) {
            Toasts.failure("Please fill in subject and details.");
            return;
        }
        // The over-limit field already shows by how much; take the user there instead of sending.
        if (LkTextLimit.exceeds(subject)) {
            subject.focus();
            return;
        }
        if (LkTextLimit.exceeds(details)) {
            details.focus();
            return;
        }
        switch (presenter.submit(AuthSession.token(),
                subject.getValue(), details.getValue(), about.getValue())) {
            case SubmitComplaintPresenter.Outcome.Success ok -> {
                Toasts.success("Complaint submitted — we'll get back to you in your Support inbox.");
                // Forward-compatible focus contract honored by SupportInboxView (#277).
                UI.getCurrent().navigate(SupportInboxView.class,
                    QueryParameters.of("c", ok.conversationId()));
            }
            case SubmitComplaintPresenter.Outcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case SubmitComplaintPresenter.Outcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not submit the complaint", fail.reason()));
        }
    }
}
