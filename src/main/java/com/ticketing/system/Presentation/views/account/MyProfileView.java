package com.ticketing.system.Presentation.views.account;
import com.ticketing.system.identity.domain.User;

import com.ticketing.system.Presentation.components.kit.Lk;
import com.ticketing.system.Presentation.components.kit.LkBadge;
import com.ticketing.system.Presentation.components.kit.LkCard;
import com.ticketing.system.Presentation.components.kit.LkCol;
import com.ticketing.system.Presentation.components.kit.LkField;
import com.ticketing.system.Presentation.components.kit.LkPage;
import com.ticketing.system.Presentation.layouts.MainLayout;
import com.ticketing.system.Presentation.presenters.account.MyProfilePresenter;
import com.ticketing.system.Presentation.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

@Route(value = "my-profile", layout = MainLayout.class)
@PageTitle("My Profile · TicketHub")
@PermitAll
public class MyProfileView extends LkPage {

    private final MyProfilePresenter presenter;

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
        // error we show a neutral placeholder rather than fabricating a plausible-looking address.
        String username = "—";
        String email = "—";
        switch (presenter.load()) {
            case MyProfilePresenter.Outcome.Success ok -> {
                username = ok.member().username();
                if (ok.member().email() != null && !ok.member().email().isBlank()) {
                    email = ok.member().email();
                }
            }
            case MyProfilePresenter.Outcome.NotAuthenticated ignored -> { /* keep placeholder */ }
            case MyProfilePresenter.Outcome.Failure ignored -> { /* keep placeholder */ }
        }

        LkCard card = new LkCard("Profile").pad(20);

        Div idRow = new Div();
        idRow.addClassName("prof-id");
        Span avatar = new Span(initials(name));
        avatar.addClassName("prof-av");
        Div info = new Div();
        Div nameDiv = new Div();
        nameDiv.addClassName("prof-name");
        nameDiv.setText(name);
        info.add(nameDiv, new LkBadge("Verified", LkBadge.Tone.success).small());
        idRow.add(avatar, info);

        LkCol fields = new LkCol().gap(14);
        fields.add(new LkField().label("Username").value(username));
        fields.add(new LkField().label("Email").value(email));

        card.add(idRow, Lk.divider(), fields);
        return card;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            String p = parts[0];
            return p.substring(0, Math.min(2, p.length())).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }
}
