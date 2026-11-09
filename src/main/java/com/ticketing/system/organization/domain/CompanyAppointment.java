package com.ticketing.system.organization.domain;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import com.ticketing.system.shared.exception.InvalidPermissionException;
import com.ticketing.system.shared.InvariantChecked;

import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A member's appointment to a role in one production company, with the permissions it grants.
 *
 * <p>An aggregate root, persisted through its own repository. It starts {@link AppointmentStatus#PENDING}, or
 * {@link AppointmentStatus#ACTIVE} for a company's founder, and moves through {@link #accept()},
 * {@link #reject()} and {@link #revoke(int)}. {@link #checkInvariants()} holds at construction and after every
 * transition: a {@link CompanyRole#Manager} lists at least one permission, an {@link CompanyRole#Owner} lists
 * none and holds all of them implicitly, and role, status and creation time are set.
 *
 * <p>Persistence: {@code appointmentId} is an assigned id minted by the repository's {@code nextId()}, as for
 * the other aggregate roots, rather than {@code @GeneratedValue}, so the Memory and Jpa adapters key it
 * identically. {@code companyId}, {@code targetId} and {@code inviterId} are plain by-id columns, never
 * associations; role and status are stored by name; permissions live in a side table. {@code @Version} drives
 * optimistic locking and lets Spring Data treat a freshly constructed appointment (null version) as an insert.
 * Fields are non-final with a protected no-argument constructor so Hibernate can hydrate; the public
 * constructor and the factories enforce the invariants.
 */
@Entity
@Table(name = "company_appointments")
public class CompanyAppointment implements InvariantChecked {
    @Id
    private int appointmentId; // assigned identifier for the appointment (minted by the repository)

    @Column(name = "company_id", nullable = false)
    private int companyId;

    @Column(name = "target_id", nullable = false)
    private int targetId; // ID of the user being appointed

    @Column(name = "inviter_id", nullable = false)
    private int inviterId; // ID of the user who created the appointment

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppointmentStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "appointment_permissions",
            joinColumns = @JoinColumn(name = "appointment_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission")
    private Set<Permission> permissions; // Permissions granted to the target user

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Version
    private Long version; // Optimistic-lock version; null on a freshly constructed (not-yet-persisted) appointment

    /** For JPA only — do not call from application code. */
    protected CompanyAppointment() { }

    /**
     * Creates an appointment and checks its invariants.
     *
     * @param appointmentId the id minted by the repository's {@code nextId()}
     * @param targetId      the user being appointed
     * @param inviterId     the user who made the appointment
     * @param permissions   the permissions granted; {@code null} or empty means none. Copied, so the caller keeps
     *                      no handle on it
     * @throws IllegalStateException if an invariant is violated: a null role, status or creation time, a Manager
     *                               without a permission, or an Owner with one
     */
    public CompanyAppointment(
            int appointmentId,
            int companyId,
            int targetId,
            int inviterId,
            CompanyRole role,
            AppointmentStatus status,
            List<Permission> permissions,
            LocalDateTime createdAt) {
        this.appointmentId = appointmentId;
        this.companyId = companyId;
        this.targetId = targetId;
        this.inviterId = inviterId;
        this.role = role;
        this.status = status;
        // Empty/null-safe: EnumSet.copyOf throws on an empty non-EnumSet collection,
        // and would
        // run before checkInvariants(). The Manager/Owner permission rules are enforced
        // below.
        this.permissions = (permissions == null || permissions.isEmpty())
                ? EnumSet.noneOf(Permission.class)
                : EnumSet.copyOf(permissions);
        this.createdAt = createdAt;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (role == null) {
            throw new IllegalStateException("CompanyAppointment invariant violated: role must not be null");
        }
        if (status == null) {
            throw new IllegalStateException("CompanyAppointment invariant violated: status must not be null");
        }
        if (permissions == null) {
            throw new IllegalStateException("CompanyAppointment invariant violated: permissions must not be null");
        }
        if (role == CompanyRole.Manager && permissions.isEmpty()) {
            throw new IllegalStateException(
                    "CompanyAppointment invariant violated: Manager role must have at least one permission");
        }
        if (role == CompanyRole.Owner && !permissions.isEmpty()) {
            throw new IllegalStateException(
                    "CompanyAppointment invariant violated: Owner role must not have explicit permissions");
        }
        if (createdAt == null) {
            throw new IllegalStateException("CompanyAppointment invariant violated: createdAt must not be null");
        }
    }

    /**
     * Creates the {@link AppointmentStatus#ACTIVE} Owner appointment of a company's founder, who holds all
     * permissions implicitly. Used when a company is opened; the registering user is both target and inviter.
     */
    public static CompanyAppointment FoundingAppointment(
            int appointmentId,
            int companyId,
            int targetId,
            int inviterId,
            LocalDateTime createdAt) {
        return new CompanyAppointment(
                appointmentId,
                companyId,
                targetId,
                inviterId,
                CompanyRole.Owner,
                AppointmentStatus.ACTIVE, // active because the founder is active immediately, used when opening a
                                          // company.
                null,
                createdAt);
    }

    /**
     * Creates a {@link AppointmentStatus#PENDING} Manager appointment (UC-24).
     *
     * @param permissions the permissions the manager is granted
     * @throws IllegalStateException if {@code permissions} is null or empty
     */
    public static CompanyAppointment ManagerAppointment(
            int appointmentId,
            int companyId,
            int targetId,
            int inviterId,
            List<Permission> permissions,
            LocalDateTime createdAt) {
        return new CompanyAppointment(
                appointmentId,
                companyId,
                targetId,
                inviterId,
                CompanyRole.Manager,
                AppointmentStatus.PENDING,
                permissions,
                createdAt);
    }

    /**
     * Creates a {@link AppointmentStatus#PENDING} Owner appointment (UC-23).
     */
    public static CompanyAppointment OwnerAppointment(
            int appointmentId,
            int companyId,
            int targetId,
            int inviterId,
            LocalDateTime createdAt) {
        return new CompanyAppointment(
                appointmentId,
                companyId,
                targetId,
                inviterId,
                CompanyRole.Owner,
                AppointmentStatus.PENDING,
                null,
                createdAt);
    }

    // ---------------------------------------------------------------------------
    // AppointmentStatus lifecycle transitions and accessors.
    // ---------------------------------------------------------------------------

    /**
     * UC-23 / UC-24 — moves a pending appointment to {@link AppointmentStatus#ACTIVE} on the target's acceptance.
     *
     * @throws IllegalStateException if the appointment is not pending
     */
    public void accept() {
        if (this.status != AppointmentStatus.PENDING) {
            throw new IllegalStateException("Only pending appointments can be accepted.");
        }
        this.status = AppointmentStatus.ACTIVE;
        checkInvariants();
    }

    /**
     * UC-23 / UC-24 — moves a pending appointment to {@link AppointmentStatus#REJECTED} on the target's rejection.
     *
     * @throws IllegalStateException if the appointment is not pending
     */
    public void reject() {
        if (this.status != AppointmentStatus.PENDING) {
            throw new IllegalStateException("Only pending appointments can be rejected.");
        }
        this.status = AppointmentStatus.REJECTED;
        checkInvariants();
    }

    /**
     * UC-24 — moves an active appointment to {@link AppointmentStatus#REVOKED}. A Manager appointment is revoked
     * by its inviter; an Owner appointment by its inviter or its target (II.4.9).
     *
     * @param revokerId the user ending the appointment
     * @throws IllegalStateException    if the appointment is not active
     * @throws IllegalArgumentException if {@code revokerId} is not a user who may revoke this appointment
     */
    public void revoke(int revokerId) {
        if (this.status != AppointmentStatus.ACTIVE) {
            throw new IllegalStateException("Only active appointments can be revoked.");
        }
        if (this.role == CompanyRole.Owner) {
            if (this.inviterId != revokerId && this.targetId != revokerId) {
                // if the appointment is an owner appointment, either the original inviter or
                // the target themselves can revoke it (per UC-24 owner revoke condition).
                throw new IllegalArgumentException("Only the original inviter or target can revoke this appointment.");
            }
        } else {
            if (this.inviterId != revokerId) {
                throw new IllegalArgumentException("Only the original inviter can revoke this appointment.");
            }
        }
        this.status = AppointmentStatus.REVOKED;
        checkInvariants();
    }

    /**
     * UC-24 — replaces an active manager appointment's permission set.
     *
     * @param newPermissions the new set; copied, so the caller keeps no handle on it
     * @throws InvalidPermissionException if this is not a manager appointment
     * @throws IllegalStateException      if the appointment is not active
     * @throws IllegalArgumentException   if {@code newPermissions} is null or empty
     */
    public void updatePermissions(Collection<Permission> newPermissions) {
        if (this.role != CompanyRole.Manager) {
            throw new InvalidPermissionException("Only manager appointments can have permissions.");
        }
        if (this.status != AppointmentStatus.ACTIVE) {
            throw new IllegalStateException("Only active appointments can have permissions updated.");
        }
        if (newPermissions == null || newPermissions.isEmpty()) {
            throw new IllegalArgumentException("Manager role must have at least one permission.");
        }
        this.permissions = EnumSet.copyOf(newPermissions);
        checkInvariants();
    }

    /**
     * Returns whether this appointment grants {@code permission}: it must be active, and either an Owner
     * appointment or a Manager appointment that lists the permission.
     */
    public boolean hasPermission(Permission permission) {
        if (this.status != AppointmentStatus.ACTIVE) {
            return false; // Inactive appointments do not grant permissions
        }
        if (this.role == CompanyRole.Owner) {
            return true; // Owners have all permissions
        }
        return this.permissions.contains(permission);
    }

    /// Getters only: permissions change through updatePermissions, and role and status through the
    /// lifecycle methods above, which enforce their rules.

    public int getCompanyId() {
        return this.companyId;
    }

    public int getTargetId() {
        return this.targetId;
    }

    public AppointmentStatus getStatus() {
        return this.status;
    }

    public CompanyRole getRole() {
        return this.role;
    }

    public int getInviterId() {
        return inviterId;
    }

    /** A read-only view: the set changes only through {@link #updatePermissions}. */
    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }

    public int getAppointmentId() {
        return this.appointmentId;
    }

    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }
}