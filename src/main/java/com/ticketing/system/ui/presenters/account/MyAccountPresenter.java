package com.ticketing.system.ui.presenters.account;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.ui.session.AuthSession;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only presenter for {@code MyAccountView}. Loads the signed-in member's
 * purchase history (orders + tickets, already enriched with event/zone names
 * and barcodes by {@code OrderReceiptMapper}).
 *
 * <p>Holds no Vaadin imports. Reconstructs the {@link AuthTokenDTO} from the
 * session because {@link MemberAccountService#viewMyHistory} takes the DTO but
 * only reads its token. Degrades to an empty history when signed out / on error.
 */
@Slf4j
@Component
public class MyAccountPresenter {

    private final MemberAccountService memberAccountService;

    public MyAccountPresenter(MemberAccountService memberAccountService) {
        this.memberAccountService = memberAccountService;
    }

    /** The signed-in member's purchase history; an empty history when signed out or on failure. */
    public Outcome loadHistory() {
        AuthTokenDTO token = currentToken();
        if (token == null) {
            return new Outcome.Success(new PurchaseHistoryDTO(List.of()));
        }
        try {
            return new Outcome.Success(memberAccountService.viewMyHistory(token));
        } catch (RuntimeException e) {
            log.warn("Failed to load purchase history: {}", e.getMessage());
            return new Outcome.Success(new PurchaseHistoryDTO(List.of()));
        }
    }

    /** Sealed outcome the view switches on to render the history or an error state. */
    public sealed interface Outcome {
        record Success(PurchaseHistoryDTO history) implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    private AuthTokenDTO currentToken() {
        String jwt = AuthSession.token();
        Integer userId = AuthSession.userId();
        if (jwt == null || userId == null) {
            return null;
        }
        return new AuthTokenDTO(jwt, AuthSession.expiresAtEpochMillis(), userId, AuthSession.displayName());
    }
}
