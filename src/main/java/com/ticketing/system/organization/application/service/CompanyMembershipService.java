package com.ticketing.system.organization.application.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.organization.application.port.out.CompanyAppointmentRepository;
import com.ticketing.system.organization.domain.AppointmentStatus;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.CompanyRole;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.exception.AppointmentNotFoundException;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;

/**
 * Owns company membership: the appointment lifecycle (offering, accepting, rejecting and revoking a role, and
 * editing a manager's permissions) and the queries that say who holds which role or permission in a company.
 *
 * <p>Each operation takes the appointed user's id and the company, and loads and persists
 * {@link CompanyAppointment} aggregates through the {@link CompanyAppointmentRepository}. New appointments are
 * stamped from the platform clock. Other contexts use the {@code require...} and {@code has...} methods to check
 * a member's role or permission.
 */
@Service
public class CompanyMembershipService {

    private final CompanyAppointmentRepository appointmentRepository;
    private final Clock clock;   // stamps new appointments (UTC wall time, ADR-008)

    /**
     * @param appointmentRepository the CompanyAppointment aggregate repository port
     * @param clock                 the platform clock new appointments are stamped from
     */
    public CompanyMembershipService(CompanyAppointmentRepository appointmentRepository, Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------
    // Lifecycle commands — each persists its change through the repository.
    // ---------------------------------------------------------------------------

    /**
     * UC-23/UC-24 — the target accepts their pending invitation. If an active appointment already
     * exists (only a Manager appointment can, alongside a pending Owner appointment), it is revoked
     * first (which enforces the "only the original inviter may promote" rule) before the pending one
     * is activated.
     *
     * @param userId    the accepting user, the appointment's target
     * @param companyId the company the pending appointment belongs to
     * @return the now-active appointment
     * @throws AppointmentNotFoundException   if the user has no pending appointment in the company
     * @throws BusinessRuleViolationException if the user already holds an active appointment that this
     *                                        acceptance may not replace: anything other than an active
     *                                        Manager appointment under a pending Owner offer, or a Manager
     *                                        appointment from a different inviter than the offer's
     */
    @Transactional
    public CompanyAppointment acceptInvitation(int userId, int companyId) {
        CompanyAppointment appointment = getPendingCompanyAppointment(userId, companyId);
        if (appointment == null) {
            throw new AppointmentNotFoundException("Cannot accept invitation: no appointment found for the specified company");
        }
        CompanyAppointment activeAppointment = getActiveCompanyAppointment(userId, companyId);
        if (activeAppointment != null) {
            // The only legal co-existence is: pending Owner offer while a Manager appointment is active
            // (accepting promotes the manager to owner). Any other combination is a rule violation.
            if (appointment.getRole() != CompanyRole.Owner || activeAppointment.getRole() != CompanyRole.Manager) {
                throw new BusinessRuleViolationException(
                        "Cannot accept invitation: user already has an active appointment in this company");
            }
            // Clearer error before revoke() throws its own: promotion requires the same appointer.
            if (activeAppointment.getInviterId() != appointment.getInviterId()) {
                throw new BusinessRuleViolationException(
                        "Cannot accept owner appointment while a manager appointment from a DIFFERENT appointer is active; revoke the manager appointment first");
            }
            // revoke() re-checks the revoker's right — which is also the right to promote to owner.
            activeAppointment.revoke(appointment.getInviterId());
            appointmentRepository.save(activeAppointment);
        }
        appointment.accept();
        return appointmentRepository.save(appointment);
    }

    /**
     * UC-23/UC-24 — the target rejects their pending invitation (PENDING -> REJECTED).
     *
     * @param userId    the rejecting user, the appointment's target
     * @param companyId the company the pending appointment belongs to
     * @throws AppointmentNotFoundException if the user has no pending appointment in the company
     */
    @Transactional
    public void rejectInvitation(int userId, int companyId) {
        CompanyAppointment appointment = getPendingCompanyAppointment(userId, companyId);
        if (appointment == null) {
            throw new AppointmentNotFoundException("Cannot reject invitation: no appointment found for the specified company");
        }
        appointment.reject();
        appointmentRepository.save(appointment);
    }

    /**
     * UC-24 — the target receives a pending Manager appointment. Fails if they already hold an active
     * or pending appointment in the company; the appointment starts PENDING and only becomes ACTIVE on
     * {@link #acceptInvitation(int, int)}.
     *
     * @param userId      the appointed user
     * @param ownerId     the appointing owner, recorded as the appointment's inviter
     * @param permissions the permissions the manager is granted; non-empty
     * @throws BusinessRuleViolationException if the user already holds an active or pending appointment
     * @throws IllegalStateException          if {@code permissions} is null or empty
     */
    @Transactional
    public void receiveManagerAppointment(int userId, int companyId, int ownerId, List<Permission> permissions) {
        if (getActiveCompanyAppointment(userId, companyId) != null
                || getPendingCompanyAppointment(userId, companyId) != null) {
            throw new BusinessRuleViolationException("User already has an active or pending appointment in this company");
        }
        CompanyAppointment appointment = CompanyAppointment.ManagerAppointment(
                appointmentRepository.nextId(),
                companyId,
                userId,
                ownerId,
                permissions,
                LocalDateTime.now(clock));
        appointmentRepository.save(appointment);
    }

    /**
     * UC-23 — the target receives a pending Owner appointment. Fails if a pending appointment already
     * exists, or if an active Owner appointment already exists (an active Manager appointment is fine —
     * accepting promotes it to Owner).
     *
     * @param userId      the appointed user
     * @param appointerId the appointing owner, recorded as the appointment's inviter
     * @throws BusinessRuleViolationException in the two cases above
     */
    @Transactional
    public void receiveOwnerAppointment(int userId, int companyId, int appointerId) {
        if (getPendingCompanyAppointment(userId, companyId) != null) {
            throw new BusinessRuleViolationException("User already has a pending appointment in this company");
        }
        CompanyAppointment activeAppointment = getActiveCompanyAppointment(userId, companyId);
        if (activeAppointment != null && activeAppointment.getRole() == CompanyRole.Owner) {
            throw new BusinessRuleViolationException("User already has an active Owner appointment in this company");
        }
        CompanyAppointment appointment = CompanyAppointment.OwnerAppointment(
                appointmentRepository.nextId(),
                companyId,
                userId,
                appointerId,
                LocalDateTime.now(clock));
        appointmentRepository.save(appointment);
    }

    /**
     * UC-18 — the founder receives an immediately-active Owner appointment on company creation (no
     * pending state). Fails if they already hold an active or pending appointment in the company.
     *
     * @param userId the founder, who is both the appointed user and the inviter
     * @throws BusinessRuleViolationException if the user already holds an active or pending appointment
     */
    @Transactional
    public void addFounderAppointment(int userId, int companyId) {
        if (getActiveCompanyAppointment(userId, companyId) != null
                || getPendingCompanyAppointment(userId, companyId) != null) {
            throw new BusinessRuleViolationException("User already has an active or pending appointment in this company");
        }
        CompanyAppointment appointment = CompanyAppointment.FoundingAppointment(
                appointmentRepository.nextId(),
                companyId,
                userId,
                userId,
                LocalDateTime.now(clock));
        appointmentRepository.save(appointment);
    }

    /**
     * UC-24 — the original appointer replaces an active Manager appointment's permission set. Only the
     * appointer may edit, and only Manager appointments carry explicit permissions.
     *
     * @param userId         the manager whose permissions change
     * @param companyId      the company the manager belongs to
     * @param inviterId      the user making the edit
     * @param newPermissions the manager's full permission set after the edit; non-empty
     * @throws AppointmentNotFoundException   if the user has no active appointment in the company
     * @throws UnauthorizedActionException    if {@code inviterId} did not make the appointment
     * @throws BusinessRuleViolationException if the active appointment is not a Manager appointment
     * @throws IllegalArgumentException       if {@code newPermissions} is null or empty
     */
    @Transactional
    public void modifyManagerPermissions(int userId, int companyId, int inviterId, List<Permission> newPermissions) {
        CompanyAppointment appointment = getActiveCompanyAppointment(userId, companyId);
        if (appointment == null) {
            throw new AppointmentNotFoundException("No active appointment to edit found for the specified company");
        }
        if (appointment.getInviterId() != inviterId) {
            throw new UnauthorizedActionException("Only the original appointer can modify manager permissions");
        }
        if (appointment.getRole() != CompanyRole.Manager) {
            throw new BusinessRuleViolationException("Can only modify permissions for manager appointments");
        }
        appointment.updatePermissions(newPermissions);
        appointmentRepository.save(appointment);
    }

    /**
     * UC-24 — revoke the user's active appointment in the company. The revoke rules (who may revoke a
     * Manager vs Owner appointment) live in {@link CompanyAppointment#revoke(int)} and throw on breach.
     *
     * @param userId    the user whose appointment ends
     * @param revokerId the user ending it
     * @throws AppointmentNotFoundException if the user has no active appointment in the company
     * @throws IllegalArgumentException     if {@code revokerId} may not revoke it
     */
    @Transactional
    public void revokeAppointment(int userId, int companyId, int revokerId) {
        CompanyAppointment appointment = getActiveCompanyAppointment(userId, companyId);
        if (appointment == null) {
            throw new AppointmentNotFoundException(
                    "Cannot remove appointment: no active appointment found to revoke for the specified company");
        }
        appointment.revoke(revokerId);
        appointmentRepository.save(appointment);
    }

    // ---------------------------------------------------------------------------
    // Read-side queries.
    // ---------------------------------------------------------------------------

    /** The user's ACTIVE appointment in the company, or {@code null} if none. */
    @Transactional(readOnly = true)
    public CompanyAppointment getActiveCompanyAppointment(int userId, int companyId) {
        for (CompanyAppointment appointment : appointmentRepository.findByTargetIdAndCompanyId(userId, companyId)) {
            if (appointment.getStatus() == AppointmentStatus.ACTIVE) {
                return appointment;
            }
        }
        return null;
    }

    /** The user's PENDING appointment in the company (invitation or owner offer), or {@code null}. */
    @Transactional(readOnly = true)
    public CompanyAppointment getPendingCompanyAppointment(int userId, int companyId) {
        for (CompanyAppointment appointment : appointmentRepository.findByTargetIdAndCompanyId(userId, companyId)) {
            if (appointment.getStatus() == AppointmentStatus.PENDING) {
                return appointment;
            }
        }
        return null;
    }

    /** Every appointment record the user holds (any company/status). */
    @Transactional(readOnly = true)
    public List<CompanyAppointment> getAllCompanyAppointments(int userId) {
        return new ArrayList<>(appointmentRepository.findByTargetId(userId));
    }

    /** Pending appointments (manager invitations + owner offers) awaiting acceptance for a company. */
    @Transactional(readOnly = true)
    public List<CompanyAppointment> findPendingAppointmentsForCompany(int companyId) {
        List<CompanyAppointment> pending = new ArrayList<>();
        for (CompanyAppointment appointment : appointmentRepository.findByCompanyId(companyId)) {
            if (appointment.getStatus() == AppointmentStatus.PENDING) {
                pending.add(appointment);
            }
        }
        return pending;
    }

    /** True iff the user holds an ACTIVE Owner appointment in the company. */
    @Transactional(readOnly = true)
    public boolean isOwnerInCompany(int userId, int companyId) {
        CompanyAppointment appointment = getActiveCompanyAppointment(userId, companyId);
        return appointment != null && appointment.getRole() == CompanyRole.Owner;
    }

    /**
     * Throws unless the user is an active owner in the company.
     *
     * @throws UnauthorizedActionException if the user is not an active owner
     */
    @Transactional(readOnly = true)
    public void requireOwnerInCompany(int userId, int companyId) {
        if (!isOwnerInCompany(userId, companyId)) {
            throw new UnauthorizedActionException("User is not an owner in this company");
        }
    }

    /** True iff the user holds any ACTIVE appointment (Owner or Manager) in the company. */
    @Transactional(readOnly = true)
    public boolean isMemberInCompany(int userId, int companyId) {
        return getActiveCompanyAppointment(userId, companyId) != null;
    }

    /**
     * Throws unless the user is an active member (Owner or Manager) of the company.
     *
     * @throws UnauthorizedActionException if the user is not an active member
     */
    @Transactional(readOnly = true)
    public void requireMemberInCompany(int userId, int companyId) {
        if (!isMemberInCompany(userId, companyId)) {
            throw new UnauthorizedActionException("User is not a member of this company");
        }
    }

    /**
     * Throws unless the user holds the given permission (via an active appointment) in the company.
     *
     * @throws UnauthorizedActionException if the user does not hold it
     */
    @Transactional(readOnly = true)
    public void requirePermissionInCompany(int userId, int companyId, Permission permission) {
        if (!hasPermissionInCompany(userId, companyId, permission)) {
            throw new UnauthorizedActionException("Missing permission: " + permission);
        }
    }

    /** True iff the user's active appointment grants the given permission (owners hold all). */
    @Transactional(readOnly = true)
    public boolean hasPermissionInCompany(int userId, int companyId, Permission permission) {
        CompanyAppointment appointment = getActiveCompanyAppointment(userId, companyId);
        if (appointment == null) {
            return false;
        }
        return appointment.hasPermission(permission);
    }
}
