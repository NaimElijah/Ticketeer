package com.ticketing.system.identity.domain;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.Core.Domain.users.AppointmentStatus; // transitional: organization type still in the legacy users package (relocated in Step 3)
import com.ticketing.system.Core.Domain.users.CompanyRole; // transitional: organization type still in the legacy users package (relocated in Step 3)
import com.ticketing.system.Core.Domain.users.Permission; // transitional: organization type still in the legacy users package (relocated in Step 3)
import com.ticketing.system.Core.Domain.users.CompanyAppointment; // transitional: organization type still in the legacy users package (relocated in Step 3)

import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;

import com.ticketing.system.shared.InvariantChecked;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

// V3: mapped to JPA. userId is an ASSIGNED @Id (minted by UserRepository.nextId(), never
// @GeneratedValue); email is unique; @Version drives optimistic locking. companyAppointments are
// owned children (@OneToMany cascade-all + orphan-removal) loaded eagerly so a detached User is
// fully usable, exactly like the in-memory version. A protected no-arg ctor lets Hibernate
// hydrate; the public ctor still enforces the invariants.
@Entity
@Table(name = "users")
public class User implements InvariantChecked {

    @Id
    private int userId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id")
    @Fetch(FetchMode.SUBSELECT)
    private List<CompanyAppointment> companyAppointments;
    // max num of pending appointments per user is 1 per company
    // max num of active appointments per user is 1 per company (can be either manager or owner, not both)
    @Column(nullable = false)
    private int age;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected User() { }

    public User(int userId, String username, String email, String password, int age) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.password = password;
        this.age = age;
        this.companyAppointments = new ArrayList<>();
        checkInvariants();
    }

    public CompanyAppointment acceptInvitation(int companyId) {
        CompanyAppointment appointment = getPendingCompanyAppointment(companyId);
        if (appointment == null) {
            throw new RuntimeException("Cannot accept invitation: no appointment found for the specified company");
        }
        CompanyAppointment activeAppointment = getActiveCompanyAppointment(companyId);
        if (activeAppointment != null) {
            // If the user already has an active appointment in the company, accepting the new one will revoke the old one (per UC-23). 
            // This can only happen if the pending appointment is an owner appointment, and the active current one is a manager appointment.
            // because of the condition rules in the receive appointment methods.

            // companyAppointments.remove(activeAppointment);
            // doesn't apply the rules of only the original inviter can edit permissions and/or revoke, and only manager appointments can be revoked by the appointer, 
            // so we can't just remove the old appointment.

            //Note: this if is only as another layer of protection to protect the rules that if another active appointment
            // exists, it can only be a manager appointment, and the pending one must be an owner appointment, because that's the only way we can 
            // have 2 appointments at the same time for the same user in the same company, per the rules in the receive appointment methods, so we check that 
            // here to make sure that the assumptions we rely on in the revoke call are correct, and to provide a clearer error message if those assumptions 
            // are violated, rather than just calling revoke and having it fail with a more obscure error if those assumptions are violated.
            if (appointment.getRole() != CompanyRole.Owner || activeAppointment.getRole() != CompanyRole.Manager) {
                throw new RuntimeException(
                        "Cannot accept invitation: user already has an active appointment in this company");
            }
            // this check is done in the revoke method ahead as well, but is here to provide an accurate error message.
            if (activeAppointment.getInviterId() != appointment.getInviterId()) {
                throw new RuntimeException(
                "Cannot accept owner appointment while a manager appointment from a DIFFERENT appointer is active; revoke the manager appointment first");
            }
            
            // ------->  we call revoke to trigger the *permission checks* and potential exceptions if the inviter doesn't have the right to revoke the old appointment,
            // in that case he also doesn't have the right to promote him to owner(only original inviter can edit his permissions).
            // so someone can't just make a manager into a new owner, without the original inviter of the manager,
            // revoking his manager appointment first(only he can change because it's his manager that he appointed).
            activeAppointment.revoke(appointment.getInviterId());  // checks are done in here to ensure that the revoker has the right to revoke this appointment, which also means they have the right to promote the user to owner by accepting the new appointment.
        }
        appointment.accept();
        return appointment;
    }

    public void rejectInvitation(int companyId) {
        CompanyAppointment appointment = getPendingCompanyAppointment(companyId);
        if (appointment == null) {
            throw new RuntimeException("Cannot reject invitation: no appointment found for the specified company");
        }
        appointment.reject();
    }

    // this receiveManagerAppointment method is used in the flow where an owner appoints a manager, and the manager receives the appointment. 
    // The checks in this method ensure that the user doesn't already have an active or pending appointment in the company, which would prevent them from receiving 
    // a new manager appointment. The appointment is created with the status PENDING, and will only become ACTIVE if the user accepts the invitation.
    public void receiveManagerAppointment(int companyId, int ownerid, List<Permission> permissions) {
        if (getActiveCompanyAppointment(companyId) != null || getPendingCompanyAppointment(companyId) != null) {
            throw new RuntimeException("User already has an active or pending appointment in this company");
        }

        CompanyAppointment appointment = CompanyAppointment.ManagerAppointment(
                0, // appointmentId would be generated by the repository in a real implementation
                companyId,
                this.userId,
                ownerid,
                permissions);

        companyAppointments.add(appointment);
        checkInvariants();
    }


    // UC-24 owner appointment receive flow. Similar to manager receive but with different checks and appointment type.
    public void receiveOwnerAppointment(int companyId, int appointerId) {
        if (getPendingCompanyAppointment(companyId) != null) {
            throw new RuntimeException("User already has an pending appointment in this company");
        }
        CompanyAppointment activeAppointment = getActiveCompanyAppointment(companyId);
        if (activeAppointment != null && activeAppointment.getRole() == CompanyRole.Owner) {
            throw new RuntimeException("User already has an active Owner appointment in this company");
        }
        // so if we got to here so the user has no pending appointment, and if they have an active appointment, it's a manager appointment, which is fine because they can be promoted to owner.
        CompanyAppointment appointment = CompanyAppointment.OwnerAppointment(
                0, // appointmentId would be generated by the repository in a real implementation
                companyId,
                this.userId,
                appointerId);

        companyAppointments.add(appointment);
        checkInvariants();
    }


    // used in the create company flow, where the founder receives an owner appointment immediately, without going through the pending state, because they are active immediately.
    public void addFounderAppointment(int companyId) {
        if (getActiveCompanyAppointment(companyId) != null || getPendingCompanyAppointment(companyId) != null) {
            throw new RuntimeException("User already has an active or pending appointment in this company");
        }

        CompanyAppointment appointment = CompanyAppointment.FoundingAppointment(
                0, // appointmentId would be generated by the repository in a real implementation
                companyId,
                this.userId,
                this.userId);

        companyAppointments.add(appointment);
        checkInvariants();
    }



    public void ModifyManagerPermissions(int companyId, int inviterId, List<Permission> newPermissions) {
        CompanyAppointment appointment = getActiveCompanyAppointment(companyId);
        if (appointment == null) {
            throw new RuntimeException("No active appointment to edit found for the specified company");
        }
        if (appointment.getInviterId() != inviterId) {// only the original appointer can modify permissions
            throw new RuntimeException("Only the original appointer can modify manager permissions");
        } // only Owners can be inviter, so there's no need to check inviter's permissions
          // separately
        if (appointment.getRole() != CompanyRole.Manager) {
            throw new RuntimeException("Can only modify permissions for manager appointments");
        }
        appointment.setPermissions(EnumSet.copyOf(newPermissions));
    }

    

    public void revokeAppointment(int companyId, int revokerId) {
        CompanyAppointment appointment = getActiveCompanyAppointment(companyId);
        if (appointment == null) {
            throw new RuntimeException("Cannot remove appointment: no active appointment found to revoke for the specified company");
        }
        appointment.revoke(revokerId);
        // logic of revoke rules inside of this revoke method, which will throw if the revoker doesn't have the right to revoke this appointment,
        // so we don't need to check separately here.
    }





    // Returns the User's appointment in the given company, or null if absent.
    public List<CompanyAppointment> getAppointmentsForCompany(int companyId) {
        List<CompanyAppointment> appointments = new ArrayList<>();
        for (CompanyAppointment appointment : companyAppointments) {
            if (appointment.getCompanyId() == companyId) {
                appointments.add(appointment);
            }
        }
        return appointments.isEmpty() ? null : appointments;
    }

    // Helper for the "view my appointments" flow (UC-20) — returns only pending
    public CompanyAppointment getActiveCompanyAppointment(int companyId) {
        for (CompanyAppointment appointment : companyAppointments) {
            if (appointment.getCompanyId() == companyId && appointment.getStatus() == AppointmentStatus.ACTIVE) {
                return appointment;
            }
        }
        return null;
    }

    // returns all pending appointments, which includes both invitations (for
    // managers) and ownership appointments awaiting acceptance.
    public CompanyAppointment getPendingCompanyAppointment(int companyId) {
        for (CompanyAppointment appointment : companyAppointments) {
            if (appointment.getCompanyId() == companyId && appointment.getStatus() == AppointmentStatus.PENDING) {
                return appointment;
            }
        }
        return null;
    }


    // Helper for permission-checking flows (UC-19/21/22/24).
    public boolean isOwnerInCompany(int companyId) {
        CompanyAppointment appointment = getActiveCompanyAppointment(companyId);
        return appointment != null && appointment.getRole() == CompanyRole.Owner;
    }

    //? these 2 functions above and below are the same logic, the below one throws and the above one returns boolean.

    // Will throw if the user is not an active owner in the company. Used to secure
    public void requireOwnerInCompany(int companyId) {
        if (!isOwnerInCompany(companyId)) {
            throw new RuntimeException("User is not an owner in this company");
        }
    }

    // True when the user holds any ACTIVE appointment (owner or manager) in the company.
    public boolean isMemberInCompany(int companyId) {
        return getActiveCompanyAppointment(companyId) != null;
    }

    /**
     * Will throw if the user is not an active member (owner or manager) of the company.
     * Used to gate read-only company-management views that any member may see — e.g. the
     * events hub a venue/sales manager opens to reach the per-event venue editor — while the
     * per-action mutations stay gated by their specific {@link Permission}.
     */
    public void requireMemberInCompany(int companyId) {
        if (!isMemberInCompany(companyId)) {
            throw new RuntimeException("User is not a member of this company");
        }
    }



    /**
     * Will throw if the user doesn't have the specified permission in the company.
     * Used to secure flows that require specific permissions.
     */
    public void requirePermissionInCompany(int companyId, Permission permission) {
        if (!hasPermissionInCompany(companyId, permission)) {
            throw new RuntimeException("Missing permission: " + permission);
        }
    }

    //? these 2 functions above and below are the same logic, the above one throws and the below one returns boolean.

    public boolean hasPermissionInCompany(int companyId, Permission permission) {
        CompanyAppointment appointment = getActiveCompanyAppointment(companyId);
        if (appointment == null) {
            return false;
        }
        return appointment.hasPermission(permission);
    }

    

    // ---------------------------------------------------------------------------
    // Skeleton additions — accessors + secure password change.
    // ---------------------------------------------------------------------------

    public int getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    /** Email registered at sign-up. Used for uniqueness checks. UC-11. */
    public String getEmail() {
        return email;
    }
public int getAge() {
    return age;
}

    /**
     * The stored password hash. UC-12. The Application layer owns the password hasher and does the
     * raw-vs-hash comparison (mirrors {@code Admin.getPasswordHash()}), keeping this entity free of
     * any Application/Infrastructure dependency.
     */
    public String getPasswordHash() {
        return password;
    }

    public List<CompanyAppointment> getAllCompanyAppointments() {
        // Defensive copy — callers must not mutate the internal appointments list.
        return new ArrayList<>(companyAppointments);
    }

    // UC-11 / future profile-edit — domain receives already-hashed password (per lecture 2). Cancelled for current work plan.
    // public void changePassword(String newHashedPassword) {
    //     throw new UnsupportedOperationException("UC-11: not implemented");
    // }

    @Override
    public void checkInvariants() {
        if (userId <= 0) {
            throw new IllegalStateException("User invariant violated: userId must be positive (was " + userId + ")");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("User invariant violated: username must be non-blank");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("User invariant violated: email must be non-blank");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("User invariant violated: password hash must be non-blank");
        }
        if (companyAppointments == null) {
            throw new IllegalStateException("User invariant violated: companyAppointments list must not be null");
        }
        if(age < 0){
            throw new IllegalStateException("User invariant violated: age cannot be negative");
        }
    }

    


}
