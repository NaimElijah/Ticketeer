package com.ticketing.system.ui.presenters.company;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.OwnerAppointmentRequestDTO;
import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;

@Component
public class MyCompaniesPresenter {

    private final CompanyManagementService companyManagementService;

    public MyCompaniesPresenter(CompanyManagementService companyManagementService) {
        this.companyManagementService = companyManagementService;
    }

    public Outcome load() {
        Integer userId = AuthSession.userId();
        if (userId == null) return new Outcome.NotAuthenticated();
        try {
            return new Outcome.Success(companyManagementService.listForUser(userId));
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    public sealed interface Outcome {
        record Success(List<UserCompanyDTO> companies) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    public UserCompanyDTO currentCompany() {
        Integer userId = AuthSession.userId();
        if (userId == null) return null;
        List<UserCompanyDTO> memberships = companyManagementService.listForUser(userId);
        if (memberships.isEmpty()) return null;
        Integer currentId = CurrentCompanies.currentCompanyId();
        if (currentId != null) {
            for (UserCompanyDTO m : memberships) {
                if (m.companyId() == currentId) return m;
            }
        }
        return memberships.get(0);
    }

    public boolean isOwnerOf(int companyId) {
        Integer userId = AuthSession.userId();
        if (userId == null) return false;
        return companyManagementService.isOwnerOf(userId, companyId);
    }

    public void selectCompany(int companyId) {
        CurrentCompanies.setCurrentCompany(companyId);
    }


    public AppointOutcome appoint(String inviteeIdentifier) {
        String token = AuthSession.token();
        if (token == null) return new AppointOutcome.NotAuthenticated();
        try {
            UserCompanyDTO company = currentCompany();
            if (company == null) return new AppointOutcome.NoCompany();
            int targetUserId = companyManagementService.resolveUserId(inviteeIdentifier);
            companyManagementService.appointOwner(token,
                    new OwnerAppointmentRequestDTO(company.companyId(), targetUserId));
            return new AppointOutcome.Success();
        } catch (InvalidTokenException e) {
            return new AppointOutcome.NotAuthenticated();
        } catch (UserNotFoundException e) {
            return new AppointOutcome.UserNotFound(inviteeIdentifier);
        } catch (RuntimeException e) {
            return new AppointOutcome.Failure(e.getMessage());
        }
    }

    public sealed interface AppointOutcome {
        record Success() implements AppointOutcome {}
        record NotAuthenticated() implements AppointOutcome {}
        record NoCompany() implements AppointOutcome {}
        record UserNotFound(String username) implements AppointOutcome {}
        record Failure(String reason) implements AppointOutcome {}
    }
}
