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
 * <p>The member is resolved through {@link SessionIdentity}, whose member token the service reads the
 * memberships for. A guest has no member token, and neither has an admin: an admin token is no member
 * credential (ADR-012). An admin, like a guest, has no companies here, and the service is not asked.
 */
@Component
public class MyCompaniesPresenter {

    private final CompanyManagementService companyManagementService;
    private final SessionIdentity identity;

    /**
     * Creates the presenter.
     *
     * @param companyManagementService reads the member's companies and appoints co-owners
     * @param identity                 resolves the live member, if any
     */
    public MyCompaniesPresenter(CompanyManagementService companyManagementService, SessionIdentity identity) {
        this.companyManagementService = companyManagementService;
        this.identity = identity;
    }

    /** The live member's companies; NotAuthenticated for a guest or an admin, Failure on error. */
    public Outcome load() {
        String memberToken = identity.memberToken();
        if (memberToken == null) return new Outcome.NotAuthenticated();
        try {
            return new Outcome.Success(companyManagementService.listForUser(memberToken));
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome of {@link #load}, which the view switches on. */
    public sealed interface Outcome {
        /**
         * The companies loaded.
         *
         * @param companies the member's active memberships, possibly none
         */
        record Success(List<UserCompanyDTO> companies) implements Outcome { }

        /** The visitor is not a live member: a guest or an admin. */
        record NotAuthenticated() implements Outcome { }

        /**
         * The load failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }

    /**
     * The live member's company in focus, by {@link SelectedCompany}'s rule; {@code null} for a guest,
     * an admin, a member of no company, or a member of several who has selected none of them or whose
     * selection is a company they do not belong to.
     */
    public UserCompanyDTO currentCompany() {
        return SelectedCompany.among(memberships(), UserCompanyDTO::companyId).orElse(null);
    }

    /** The live member's memberships; none for a guest or an admin. */
    private List<UserCompanyDTO> memberships() {
        String memberToken = identity.memberToken();
        if (memberToken == null) return List.of();
        return companyManagementService.listForUser(memberToken);
    }

    /** Whether the live member owns {@code companyId}; {@code false} for a guest or an admin. */
    public boolean isOwnerOf(int companyId) {
        String memberToken = identity.memberToken();
        if (memberToken == null) return false;
        return companyManagementService.isOwnerOf(memberToken, companyId);
    }

    /**
     * Makes {@code companyId} the company selected in the workspace ({@link CurrentCompanies}). Whether
     * the member may act on it stays the services' check.
     *
     * @param companyId the company the member chose
     */
    public void selectCompany(int companyId) {
        CurrentCompanies.setCurrentCompany(companyId);
    }


    /**
     * Invites a member to co-own the selected company. The company is picked by
     * {@link SelectedCompany}'s rule from the member's companies, and nothing is appointed when none is
     * picked.
     *
     * @param inviteeIdentifier the invitee as typed; the service resolves it
     * @return {@link AppointOutcome.NoCompany} when the member belongs to no company or the stored
     *     token is no longer live;
     *     {@link AppointOutcome.ChooseCompany} when no single company can be picked;
     *     {@link AppointOutcome.UserNotFound} when the service cannot resolve the invitee;
     *     {@link AppointOutcome.NotAuthenticated} when no token is stored, or the service rejects it
     *     after the live check;
     *     {@link AppointOutcome.Failure} with {@code ServiceErrors}' text for any other failure
     */
    public AppointOutcome appoint(String inviteeIdentifier) {
        String token = AuthSession.token();
        if (token == null) return new AppointOutcome.NotAuthenticated();
        try {
            List<UserCompanyDTO> memberships = memberships();
            if (memberships.isEmpty()) return new AppointOutcome.NoCompany();
            Optional<UserCompanyDTO> company = SelectedCompany.among(memberships, UserCompanyDTO::companyId);
            if (company.isEmpty()) return new AppointOutcome.ChooseCompany();
            int targetUserId = companyManagementService.resolveUserId(token, inviteeIdentifier);
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

    /** Sealed outcome of {@link #appoint}, which the view switches on. */
    public sealed interface AppointOutcome {
        /** The co-owner invitation is sent. */
        record Success() implements AppointOutcome {}

        /** No token is stored, or the service rejected it after the live check. */
        record NotAuthenticated() implements AppointOutcome {}

        /** The member belongs to no company, or the stored token is no longer live. */
        record NoCompany() implements AppointOutcome {}

        /**
         * The member belongs to several companies and has selected none of them, or the selection is a
         * company they do not belong to.
         */
        record ChooseCompany() implements AppointOutcome {}

        /**
         * The service could not resolve the invitee.
         *
         * @param username the invitee as the member typed it
         */
        record UserNotFound(String username) implements AppointOutcome {}

        /**
         * The service refused the appointment or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements AppointOutcome {}
    }
}
