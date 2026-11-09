package com.ticketing.system.ui.views.account;

import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.account.MyProfilePresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.support.DisplayLocale;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.DescriptionList;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

/**
 * Profile page, served at {@code /my-profile}. It shows the signed-in member's username and email.
 * For members only: an admin, who is no member, never reaches it (ADR-012).
 */
@Route(value = "my-profile", layout = MainLayout.class)
@PageTitle("My Profile · TicketHub")
@PermitAll
@RequireCapability(Capability.MEMBER_ACCOUNT)
public class MyProfileView extends LkPage {

    private final MyProfilePresenter presenter;

    /**
     * Creates the page and loads the member's profile.
     */
    public MyProfileView(MyProfilePresenter presenter) {
        this.presenter = presenter;
        title("My Profile");
        subtitle("Your account details.");
        add(buildProfileCard());
    }

    private Component buildProfileCard() {
        String name = AuthSession.displayName();
        if (name == null || name.isBlank()) name = "Guest";

        // Username + email come from the User aggregate via the presenter. When signed out or on a load
        // error we show a neutral placeholder rather than fabricating a plausible-looking address, and a
        // load error also says why.
        String username = "—";
        String email = "—";
        LkBanner loadFailure = null;
        switch (presenter.load()) {
            case MyProfilePresenter.Outcome.Success ok -> {
                username = ok.member().username();
                if (ok.member().email() != null && !ok.member().email().isBlank()) {
                    email = ok.member().email();
                }
            }
            // The route needs MEMBER_ACCOUNT, so only a token that died after the check gets here (#122).
            case MyProfilePresenter.Outcome.NotAuthenticated ignored -> loadFailure = new LkBanner(LkBanner.Tone.warn,
                new LkIcon("warning", 18), "Your session has expired — please sign in again.");
            case MyProfilePresenter.Outcome.Failure fail -> loadFailure = new LkBanner(LkBanner.Tone.warn,
                new LkIcon("warning", 18), Lk.withReason("Could not load your profile", fail.reason()));
        }

        LkCard card = new LkCard("Profile").pad(20);
        if (loadFailure != null) {
            card.add(loadFailure);
        }

        Div idRow = new Div();
        idRow.addClassName("prof-id");
        Span avatar = new Span(initials(name));
        avatar.addClassName("prof-av");
        Div info = new Div();
        Div nameDiv = new Div();
        nameDiv.addClassName("prof-name");
        nameDiv.setText(name);
        // No "Verified" badge until #103 verifies accounts: https://github.com/NaimElijah/Ticketeer/issues/103
        info.add(nameDiv);
        idRow.add(avatar, info);

        // Read-only values are a description list, not inputs: a value drawn as an input inside a label
        // reads as a field the user can edit (#188, WCAG 1.3.1, 4.1.2).
        DescriptionList fields = new DescriptionList();
        fields.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "14px").set("margin", "0");
        fields.add(readOnlyValue("Username", username), readOnlyValue("Email", email));

        card.add(idRow, Lk.divider(), fields);
        return card;
    }

    private static Div readOnlyValue(String label, String value) {
        DescriptionList.Term term = new DescriptionList.Term(label);
        term.addClassName("lk-label");
        DescriptionList.Description description = new DescriptionList.Description(value == null ? "" : value);
        description.getStyle().set("margin", "0");
        Div pair = new Div(term, description);
        pair.addClassName("lk-field");
        return pair;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            String p = parts[0];
            return p.substring(0, Math.min(2, p.length())).toUpperCase(DisplayLocale.PAGE);
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase(DisplayLocale.PAGE);
    }
}
