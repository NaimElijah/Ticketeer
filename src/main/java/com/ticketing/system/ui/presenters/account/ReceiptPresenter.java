package com.ticketing.system.ui.presenters.account;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TransactionRecordDTO;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Read-only presenter for {@code ReceiptView} (#276). Loads one member-owned order receipt and
 * returns a sealed {@link Outcome} the view switches on — the view never calls the service
 * directly.
 *
 * <p>Takes the member's token (the view passes {@code SessionIdentity.memberToken()}, which is
 * {@code null} for guests → an auth {@link Outcome.Failure}). A receipt that belongs to another
 * account gives the same {@link Outcome.NotFound} as a missing id, so a member cannot tell which
 * receipt ids exist on other accounts.
 */
@Component
public class ReceiptPresenter {

    private static final String RECEIPT_NOT_FOUND_MESSAGE = "We couldn't find that receipt.";
    private static final String REFUND_TRANSACTION_TYPE = "REFUND";

    private final MemberAccountService memberAccountService;

    /** Creates the presenter over the member account service. */
    public ReceiptPresenter(MemberAccountService memberAccountService) {
        this.memberAccountService = memberAccountService;
    }

    /**
     * Loads one receipt of the signed-in member.
     *
     * @param token     the member's token; {@code null} or a rejected token gives a {@link Outcome.Failure}
     *     asking the user to sign in
     * @param receiptId the receipt to load
     * @return {@link Outcome.Success} with the receipt; {@link Outcome.NotFound} when no such receipt
     *     exists or it belongs to another account; {@link Outcome.Failure} for any other failure
     */
    public Outcome load(String token, int receiptId) {
        try {
            PurchaseRecordDTO receipt = memberAccountService.viewMyReceipt(token, receiptId);
            return new Outcome.Success(receipt, refundsOf(receipt));
        } catch (EntityNotFoundException | UnauthorizedActionException e) {
            return new Outcome.NotFound(RECEIPT_NOT_FOUND_MESSAGE);
        } catch (InvalidTokenException e) {
            return new Outcome.Failure("Please sign in to view this receipt.");
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessageLogged(e, "load receipt receiptId=" + receiptId));
        }
    }

    // An order can be refunded more than once, for example by two cancelled events, so every refund
    // transaction counts (#187, UI-A5).
    private static Optional<Refunds> refundsOf(PurchaseRecordDTO receipt) {
        List<TransactionRecordDTO> refunds = receipt.transactions().stream()
                .filter(tx -> REFUND_TRANSACTION_TYPE.equals(tx.type()))
                .toList();
        if (refunds.isEmpty()) {
            return Optional.empty();
        }
        double total = refunds.stream().mapToDouble(TransactionRecordDTO::amount).sum();
        LocalDateTime lastRefundedAt = refunds.stream()
                .map(TransactionRecordDTO::timestamp)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return Optional.of(new Refunds(total, lastRefundedAt));
    }

    /**
     * Every refund of one receipt, taken together.
     *
     * @param total          the sum of the receipt's refund transactions
     * @param lastRefundedAt when the latest of them was made, or {@code null} when none carries a time
     */
    public record Refunds(double total, LocalDateTime lastRefundedAt) { }

    /** Sealed outcome the view switches on to render the receipt or an error banner. */
    public sealed interface Outcome {
        /**
         * @param receipt the member's receipt
         * @param refunds its refunds summed, or empty when it has none
         */
        record Success(PurchaseRecordDTO receipt, Optional<Refunds> refunds) implements Outcome { }

        /**
         * No receipt with that id exists for this member.
         *
         * @param message fixed text for the user
         */
        record NotFound(String message)  implements Outcome { }

        /**
         * The load failed, or the visitor has to sign in.
         *
         * @param message the text the user may read
         */
        record Failure(String message)   implements Outcome { }
    }
}
