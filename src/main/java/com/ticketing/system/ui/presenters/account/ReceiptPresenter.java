package com.ticketing.system.ui.presenters.account;

import org.springframework.stereotype.Component;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.identity.application.service.MemberAccountService;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only presenter for {@code ReceiptView} (#276). Loads one member-owned order receipt and
 * returns a sealed {@link Outcome} the view switches on — the view never calls the service
 * directly nor uses {@code try/catch}.
 *
 * <p>Takes the member's token (the view passes {@code SessionIdentity.memberToken()}, which is
 * {@code null} for guests → an auth {@link Outcome.Failure}). A receipt that belongs to another
 * account surfaces as {@link Outcome.Forbidden} (the issue's "403").
 */
@Slf4j
@Component
public class ReceiptPresenter {

    private final MemberAccountService memberAccountService;

    public ReceiptPresenter(MemberAccountService memberAccountService) {
        this.memberAccountService = memberAccountService;
    }

    public Outcome load(String token, int receiptId) {
        try {
            return new Outcome.Success(memberAccountService.viewMyReceipt(token, receiptId));
        } catch (EntityNotFoundException e) {
            return new Outcome.NotFound("We couldn't find that receipt.");
        } catch (UnauthorizedActionException e) {
            return new Outcome.Forbidden("This receipt belongs to another account.");
        } catch (InvalidTokenException e) {
            return new Outcome.Failure("Please sign in to view this receipt.");
        } catch (RuntimeException e) {
            log.warn("Failed to load receipt {}: {}", receiptId, e.getMessage());
            return new Outcome.Failure("We couldn't load this receipt right now.");
        }
    }

    /** Sealed outcome the view switches on to render the receipt or an error banner. */
    public sealed interface Outcome {
        record Success(PurchaseRecordDTO receipt) implements Outcome { }
        record NotFound(String message)  implements Outcome { }
        record Forbidden(String message) implements Outcome { }
        record Failure(String message)   implements Outcome { }
    }
}
