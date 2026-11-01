package com.ticketing.system.ui.presenters.account;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Read-only presenter for {@code MyAccountView}. Loads the signed-in member's
 * purchase history (orders + tickets, already enriched with event/zone names
 * and barcodes by {@code OrderReceiptMapper}).
 *
 * <p>Holds no Vaadin imports. Resolves the member through {@link SessionIdentity}, as every member
 * path does, and builds the {@link AuthTokenDTO} {@link MemberAccountService#viewMyHistory} takes
 * (it reads only the token). Gives an empty history to a visitor who is not a live member, an admin
 * included, and a failure outcome, never an empty history, when the load fails.
 */
@Component
public class MyAccountPresenter {

    private final MemberAccountService memberAccountService;
    private final SessionIdentity identity;

    public MyAccountPresenter(MemberAccountService memberAccountService, SessionIdentity identity) {
        this.memberAccountService = memberAccountService;
        this.identity = identity;
    }

    /**
     * The signed-in member's purchase history.
     *
     * @return {@code Success} with the history (empty, without calling the service, for a visitor who
     *         is not a live member: a guest, an admin, or a dead token), or {@code Failure} with a
     *         {@link ServiceErrors} message when the service refuses the token or fails, so a failed
     *         load is never shown as "no purchases" (#192)
     */
    public Outcome loadHistory() {
        AuthTokenDTO token = currentMemberToken();
        if (token == null) {
            return new Outcome.Success(new PurchaseHistoryDTO(List.of()));
        }
        try {
            return new Outcome.Success(memberAccountService.viewMyHistory(token));
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome the view switches on to render the history or an error state. */
    public sealed interface Outcome {
        record Success(PurchaseHistoryDTO history) implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    // Only a live member's token: an admin's session stores a token too, and admin and member ids share
    // one space (ADR-012), so AuthSession alone would send an admin's token down a member path.
    private AuthTokenDTO currentMemberToken() {
        String memberToken = identity.memberToken();
        if (memberToken == null) {
            return null;
        }
        return new AuthTokenDTO(memberToken, AuthSession.expiresAtEpochMillis(), identity.memberUserId(),
                AuthSession.displayName());
    }
}
