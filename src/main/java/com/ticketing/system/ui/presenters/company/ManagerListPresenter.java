package com.ticketing.system.ui.presenters.company;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.shared.dto.AppointmentRevokeDTO;
import com.ticketing.system.organization.application.dto.PermissionEditDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code ManagerListView}. Holds no Vaadin imports so the
 * outcome → UI translation lives in the view and the service-call decision tree
 * is unit-testable in isolation (the view reads the token from {@code AuthSession}
 * and passes it in, mirroring {@code LoginPresenter}).
 *
 * <p>Resolves the company selected in the workspace ({@code CurrentCompanies}) among the
 * companies the token's owner owns, then loads the active-manager and pending-invitation
 * rosters for it. Returns a typed {@link Outcome} so the view's job is a switch over the
 * sealed hierarchy.
 */
@Component
public class ManagerListPresenter {

    private final CompanyManagementService companyManagementService;

    @Autowired
    public ManagerListPresenter(CompanyManagementService companyManagementService) {
        this.companyManagementService = companyManagementService;
    }

    /**
     * Loads the manager roster of the selected company.
     *
     * @return {@link Outcome.NoCompany} when the caller owns no company; {@link Outcome.ChooseCompany}
     *     when they own one or more but none of them is selected, or the selection is one they do not own
     */
    public Outcome loadRoster(String token) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            List<ProductionCompanyDTO> owned = companyManagementService.findOwnedCompanies(token);
            if (owned.isEmpty()) {
                return new Outcome.NoCompany();
            }
            Optional<ProductionCompanyDTO> selected = SelectedCompany.among(owned, ProductionCompanyDTO::companyId);
            if (selected.isEmpty()) {
                return new Outcome.ChooseCompany();
            }
            ProductionCompanyDTO company = selected.get();
            List<AppointmentInfoDTO> active =
                companyManagementService.listManagers(token, company.companyId());
            List<AppointmentInfoDTO> pending =
                companyManagementService.listPendingInvitations(token, company.companyId());
            return new Outcome.Success(company.name(), active, pending);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Updates a manager's permission set (V2-CADMIN-03). Permission names are the
     * {@link Permission} enum constants the dialog collected; the backend rejects an
     * empty set and restricts the edit to the manager's original appointer — both
     * surface here as {@link ActionOutcome.Failure}.
     */
    public ActionOutcome editPermissions(String token, int companyId, int targetUserId,
                                         List<String> permissionNames) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            List<Permission> permissions = permissionNames.stream().map(Permission::valueOf).toList();
            companyManagementService.editManagerPermissions(
                token, new PermissionEditDTO(companyId, targetUserId, permissions));
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Revokes a manager appointment (V2-CADMIN-03). The backend forbids revoking the
     * founder and restricts the action to the original appointer — surfaced here as
     * {@link ActionOutcome.Failure}.
     */
    public ActionOutcome revoke(String token, int companyId, int targetUserId) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            companyManagementService.RevokeAppointment(
                token, new AppointmentRevokeDTO(companyId, targetUserId));
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome the view switches on to render the grids or an empty state. */
    public sealed interface Outcome {
        record Success(String companyName,
                       List<AppointmentInfoDTO> activeManagers,
                       List<AppointmentInfoDTO> pendingInvitations) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        /** The caller owns no company. */
        record NoCompany() implements Outcome { }
        /** The caller owns a company, but none of theirs is selected in the workspace. */
        record ChooseCompany() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** Result of a manager mutation (edit permissions / revoke) the view reacts to. */
    public sealed interface ActionOutcome {
        record Success() implements ActionOutcome { }
        record NotAuthenticated() implements ActionOutcome { }
        record Failure(String reason) implements ActionOutcome { }
    }
}
