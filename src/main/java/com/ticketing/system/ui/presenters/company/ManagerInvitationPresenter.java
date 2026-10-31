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

    @Autowired
    public ManagerInvitationPresenter(CompanyManagementService companyService) {
        this.companyService = companyService;
    }

    /**
     * Appoints {@code inviteeUsername} as a pending manager of the selected company.
     *
     * @return {@link Outcome.NoCompany} when the caller owns no company; {@link Outcome.ChooseCompany}
     *     when they own one or more but none of them is selected, or the selection is one they do not
     *     own. The appointment is not attempted then
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

    public sealed interface Outcome {
        record Success() implements Outcome {}
        record NotAuthenticated() implements Outcome {}
        /** The caller owns no company. */
        record NoCompany() implements Outcome {}
        /** The caller owns a company, but none of theirs is selected in the workspace. */
        record ChooseCompany() implements Outcome {}
        record UserNotFound(String username) implements Outcome {}
        record Failure(String reason) implements Outcome {}
    }
}