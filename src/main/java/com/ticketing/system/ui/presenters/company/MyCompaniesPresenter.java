package com.ticketing.system.ui.presenters.company;

import java.util.List;
import java.util.Optional;

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

    /**
     * The live member's company in focus, by {@link SelectedCompany}'s rule; {@code null} for a guest,
     * an admin, a member of no company, or a member of several who has selected none of them.
     */
    public UserCompanyDTO currentCompany() {
        return SelectedCompany.among(memberships(), UserCompanyDTO::companyId).orElse(null);
    }

    /** The live member's memberships; none for a guest or an admin. */
    private List<UserCompanyDTO> memberships() {
        int userId = identity.memberUserId();
        if (userId == SessionIdentity.NO_MEMBER_USER_ID) return List.of();
        return companyManagementService.listForUser(userId);
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
            List<UserCompanyDTO> memberships = memberships();
            if (memberships.isEmpty()) return new AppointOutcome.NoCompany();
            Optional<UserCompanyDTO> company = SelectedCompany.among(memberships, UserCompanyDTO::companyId);
            if (company.isEmpty()) return new AppointOutcome.ChooseCompany();
            int targetUserId = companyManagementService.resolveUserId(inviteeIdentifier);
            companyManagementService.appointOwner(token,
                    new OwnerAppointmentRequestDTO(company.get().companyId(), targetUserId));
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
        /** The member belongs to several companies and has not selected one of them. */
        record ChooseCompany() implements AppointOutcome {}
        record UserNotFound(String username) implements AppointOutcome {}
        record Failure(String reason) implements AppointOutcome {}
    }
}
