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
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for {@code MyCompaniesView} and {@code OwnerAppointmentView}: the live member's companies,
 * the one in focus, and co-owner appointment.
 *
 * <p>The member is resolved through {@link SessionIdentity}: an admin's session stores the admin's id,
 * and admin and member ids share one id space (ADR-012), so the stored id alone would show an admin the
 * companies of the member with that id. An admin, like a guest, has no companies here.
 */
@Component
public class MyCompaniesPresenter {

    private final CompanyManagementService companyManagementService;
    private final SessionIdentity identity;

    public MyCompaniesPresenter(CompanyManagementService companyManagementService, SessionIdentity identity) {
        this.companyManagementService = companyManagementService;
        this.identity = identity;
    }

    /** The live member's companies; NotAuthenticated for a guest or an admin, Failure on error. */
    public Outcome load() {
        int userId = identity.memberUserId();
        if (userId == SessionIdentity.NO_MEMBER_USER_ID) return new Outcome.NotAuthenticated();
        try {
            return new Outcome.Success(companyManagementService.listForUser(userId));
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    public sealed interface Outcome {
        record Success(List<UserCompanyDTO> companies) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** The live member's company in focus, else their first; {@code null} for a guest, an admin, or none. */
    public UserCompanyDTO currentCompany() {
        int userId = identity.memberUserId();
        if (userId == SessionIdentity.NO_MEMBER_USER_ID) return null;
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

    /** Whether the live member owns {@code companyId}; {@code false} for a guest or an admin. */
    public boolean isOwnerOf(int companyId) {
        int userId = identity.memberUserId();
        if (userId == SessionIdentity.NO_MEMBER_USER_ID) return false;
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
            return new AppointOutcome.Failure(ServiceErrors.userMessage(e));
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
