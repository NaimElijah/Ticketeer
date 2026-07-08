package com.ticketing.system.Presentation.presenters.account;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.AppointmentResponseDTO;
import com.ticketing.system.Core.Application.dto.InvitationDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;

/**
 * MVP presenter for {@code MyInvitationsView}. Holds no Vaadin imports so the
 * outcome → UI translation lives in the view and the service-call decision tree
 * is unit-testable in isolation (the view passes {@code AuthSession.token()} in,
 * mirroring {@code ManagerListPresenter}).
 *
 * <p>Loads the member's own invitation records and splits them into the still-open
 * {@code PENDING} offers and the resolved {@code ACTIVE/REJECTED/REVOKED} history.
 * Accept/decline reuse the backend {@code respondToAppointment} flow, keyed by
 * company id (one pending appointment per user per company).
 */
@Component
public class MyInvitationsPresenter {

    private static final String PENDING = "PENDING";

    private final CompanyManagementService companyManagementService;

    @Autowired
    public MyInvitationsPresenter(CompanyManagementService companyManagementService) {
        this.companyManagementService = companyManagementService;
    }

    /** Loads the signed-in member's invitations, split into pending vs resolved history. */
    public Outcome load(String token) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            List<InvitationDTO> all = companyManagementService.listMyInvitations(token);
            List<InvitationDTO> pending = all.stream()
                .filter(i -> PENDING.equals(i.status()))
                .toList();
            List<InvitationDTO> history = all.stream()
                .filter(i -> !PENDING.equals(i.status()))
                .toList();
            return new Outcome.Success(pending, history);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** Accepts the pending invitation for {@code companyId} (flips the member's role). */
    public ActionOutcome accept(String token, int companyId) {
        return respond(token, companyId, true);
    }

    /** Declines the pending invitation for {@code companyId}. */
    public ActionOutcome decline(String token, int companyId) {
        return respond(token, companyId, false);
    }

    private ActionOutcome respond(String token, int companyId, boolean accept) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            companyManagementService.respondToAppointment(
                token, new AppointmentResponseDTO(companyId, accept));
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome the view switches on to render the grids or an empty state. */
    public sealed interface Outcome {
        record Success(List<InvitationDTO> pending, List<InvitationDTO> history) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** Result of an accept/decline action the view reacts to. */
    public sealed interface ActionOutcome {
        record Success() implements ActionOutcome { }
        record NotAuthenticated() implements ActionOutcome { }
        record Failure(String reason) implements ActionOutcome { }
    }
}
