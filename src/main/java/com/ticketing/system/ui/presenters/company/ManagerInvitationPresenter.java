package com.ticketing.system.ui.presenters.company;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.organization.application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code ManagerInvitationView}: invites a member to manage the company selected in
 * the workspace ({@code CurrentCompanies}), which the caller must own.
 */
@Component
public class ManagerInvitationPresenter {

    private final CompanyManagementService companyService;

    /** Creates the presenter over the company management service. */
    @Autowired
    public ManagerInvitationPresenter(CompanyManagementService companyService) {
        this.companyService = companyService;
    }

    /**
     * Appoints {@code inviteeUsername} as a pending manager of the selected company.
     *
     * @param token           the caller's member token; {@code null} gives {@link Outcome.NotAuthenticated}
     * @param inviteeUsername the invitee, as a username or an email address, as typed
     * @param permissions     the permissions the manager would hold once the invitation is accepted
     * @return {@link Outcome.NoCompany} when the caller owns no company; {@link Outcome.ChooseCompany}
     *     when they own several and have selected none of them, or the selection is a company they do
     *     not own. The appointment is not attempted then
     */
    public Outcome invite(String token, String inviteeUsername, List<Permission> permissions) {
        if (token == null) return new Outcome.NotAuthenticated();
        try {
            List<ProductionCompanyDTO> owned = companyService.findOwnedCompanies(token);
            if (owned.isEmpty()) return new Outcome.NoCompany();
            Optional<ProductionCompanyDTO> selected = SelectedCompany.among(owned, ProductionCompanyDTO::companyId);
            if (selected.isEmpty()) return new Outcome.ChooseCompany();
            int companyId = selected.get().companyId();
            int targetUserId = companyService.resolveUserId(token, inviteeUsername);
            companyService.appointManager(token,
                new ManagerAppointmentRequestDTO(companyId, targetUserId, permissions));
            return new Outcome.Success();
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (UserNotFoundException e) {
            return new Outcome.UserNotFound(inviteeUsername);
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome of {@link #invite}, which the view switches on. */
    public sealed interface Outcome {
        /** The invitation is sent; the appointment stays pending until the invitee answers. */
        record Success() implements Outcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements Outcome {}

        /** The caller owns no company. */
        record NoCompany() implements Outcome {}

        /**
         * The caller owns several companies and has selected none of them, or the selection is a company
         * they do not own.
         */
        record ChooseCompany() implements Outcome {}

        /**
         * No member matches the invitee.
         *
         * @param username the invitee as the caller typed it
         */
        record UserNotFound(String username) implements Outcome {}

        /**
         * The service refused the appointment or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome {}
    }
}