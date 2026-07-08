package com.ticketing.system.ui.presenters.company;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.Core.Application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.Core.Domain.users.Permission;

@Component
public class ManagerInvitationPresenter {

    private final CompanyManagementService companyService;

    @Autowired
    public ManagerInvitationPresenter(CompanyManagementService companyService) {
        this.companyService = companyService;
    }

    public Outcome invite(String token, String inviteeUsername, List<Permission> permissions) {
        if (token == null) return new Outcome.NotAuthenticated();
        try {
            List<ProductionCompanyDTO> owned = companyService.findOwnedCompanies(token);
            if (owned.isEmpty()) return new Outcome.NoCompany();
            int companyId = owned.get(0).companyId();
            int targetUserId = companyService.resolveUserId(inviteeUsername);
            companyService.appointManager(token,
                new ManagerAppointmentRequestDTO(companyId, targetUserId, permissions));
            return new Outcome.Success();
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (UserNotFoundException e) {
            return new Outcome.UserNotFound(inviteeUsername);
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    public sealed interface Outcome {
        record Success() implements Outcome {}
        record NotAuthenticated() implements Outcome {}
        record NoCompany() implements Outcome {}
        record UserNotFound(String username) implements Outcome {}
        record Failure(String reason) implements Outcome {}
    }
}