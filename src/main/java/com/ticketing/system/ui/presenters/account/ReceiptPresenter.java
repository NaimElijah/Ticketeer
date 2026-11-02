package com.ticketing.system.ui.presenters.account;

import org.springframework.stereotype.Component;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Read-only presenter for {@code ReceiptView} (#276). Loads one member-owned order receipt and
 * returns a sealed {@link Outcome} the view switches on — the view never calls the service
 * directly nor uses {@code try/catch}.
 *
 * <p>Takes the member's token (the view passes {@code SessionIdentity.memberToken()}, which is
 * {@code null} for guests → an auth {@link Outcome.Failure}). A receipt that belongs to another
 * account gives the same {@link Outcome.NotFound} as a missing id, so a member cannot tell which
 * receipt ids exist on other accounts.
 */
@Component
public class ReceiptPresenter {

    private static final String RECEIPT_NOT_FOUND_MESSAGE = "We couldn't find that receipt.";

    private final MemberAccountService memberAccountService;

    public ReceiptPresenter(MemberAccountService memberAccountService) {
        this.memberAccountService = memberAccountService;
    }

    public Outcome load(String token, int receiptId) {
        try {
            return new Outcome.Success(memberAccountService.viewMyReceipt(token, receiptId));
        } catch (EntityNotFoundException | UnauthorizedActionException e) {
            return new Outcome.NotFound(RECEIPT_NOT_FOUND_MESSAGE);
        } catch (InvalidTokenException e) {
            return new Outcome.Failure("Please sign in to view this receipt.");
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessageLogged(e, "load receipt receiptId=" + receiptId));
        }
    }

    /** Sealed outcome the view switches on to render the receipt or an error banner. */
    public sealed interface Outcome {
        record Success(PurchaseRecordDTO receipt) implements Outcome { }
        record NotFound(String message)  implements Outcome { }
        record Failure(String message)   implements Outcome { }
    }
}
