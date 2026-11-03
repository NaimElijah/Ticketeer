package com.ticketing.system.ui.views.company;

import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkFormErrors;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.company.CompanyRegistrationPresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.shared.HasValidationProperties;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

/**
 * Register-a-company form — the entry point for a registered member to
 * become an organizer. It is the workspace fallback destination for a member
 * with no company, so its capability, {@link Capability#REGISTER_COMPANY}, is
 * one every member holds, owner or not; a signed-in admin, who is no member,
 * is turned away (#187, UI-A8).
 */
@Route(value = "register-company", layout = MainLayout.class)
@PageTitle("Register a Company · TicketHub")
@PermitAll
@RequireCapability(Capability.REGISTER_COMPANY)
public class CompanyRegistrationView extends LkPage {

    private final CompanyRegistrationPresenter presenter;
    private final LkFormErrors errors = new LkFormErrors();

    private final TextField name = new TextField("Company name");
    private final TextArea description = new TextArea("Description");

    public CompanyRegistrationView(CompanyRegistrationPresenter presenter) {
        this.presenter = presenter;
        title("Register a Production Company");
        subtitle("Found a new company — you become its immutable founder.");
        add(buildForm());
    }

    private Component buildForm() {
        Div narrow = new Div();
        narrow.addClassName("form-narrow");

        LkCard card = new LkCard("Company Details").pad(20);

        name.setPlaceholder("e.g. BlueWave Productions");
        name.setRequired(true);
        name.setWidthFull();
        LkTextLimit.apply(name);

        description.setPlaceholder("What kind of events does this company produce?");
        description.setRequired(true);
        description.setMinHeight("120px");
        description.setWidthFull();
        LkTextLimit.apply(description);

        LkCol col = new LkCol().gap(14);
        col.add(name, description);
        card.add(col);

        narrow.add(card);
        narrow.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 17),
                "The new company starts Active and you are recorded as the founder. You can invite owners and managers afterwards."));

        LkRow actions = new LkRow().gap(8).justify("flex-end");
        actions.add(
                new LkBtn("Cancel").variant(LkBtn.Variant.tertiary)
                        .onClick(e -> UI.getCurrent().navigate(MyCompaniesView.class)),
                new LkBtn("Register Company").variant(LkBtn.Variant.primary)
                        .icon(new LkIcon("plus", 15))
                        .onClick(e -> attemptRegister()));
        narrow.add(actions);
        return narrow;
    }

    private void attemptRegister() {
        errors.clear();
        if (name.isEmpty()) {
            refuse(name, "Please fill in a company name.");
            return;
        }

        String descriptionValue = description.getValue() == null ? "" : description.getValue().trim();
        if (descriptionValue.isEmpty()) {
            refuse(description, "Please provide a company description.");
            return;
        }

        CompanyRegistrationPresenter.Outcome outcome = presenter.register(
                name.getValue().trim(),
                descriptionValue);

        switch (outcome) {
            case CompanyRegistrationPresenter.Outcome.Success success ->
                Toasts.success("'" + success.company().name() + "' registered — welcome to the organizer workspace.");
            case CompanyRegistrationPresenter.Outcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case CompanyRegistrationPresenter.Outcome.InvalidInput invalid ->
                Toasts.failure(Lk.withReason("Please check the company details", invalid.reason()));
            case CompanyRegistrationPresenter.Outcome.NameTaken taken ->
                refuse(name, "That company name is already taken — please choose another.");
            case CompanyRegistrationPresenter.Outcome.Failure fail ->
                Toasts.failure(Lk.withReason("Registration failed", fail.reason()));
        }

        if (outcome instanceof CompanyRegistrationPresenter.Outcome.Success) {
            UI.getCurrent().navigate(OwnerDashboardView.class);
        }
    }

    // The toast stays, but the field now carries the message too, and takes focus (#188).
    private <F extends Component & HasValidationProperties & Focusable<?>> void refuse(F field, String message) {
        errors.reject(field, message);
        Toasts.failure(message);
        errors.focusFirst();
    }
}
