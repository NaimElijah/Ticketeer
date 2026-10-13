package com.ticketing.system.organization.domain;
import com.ticketing.system.organization.domain.CompanyStatus;

import java.util.ArrayList;
import java.util.List;

import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.InvariantChecked;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicyJsonConverter;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

// V3: mapped to JPA. companyId is an ASSIGNED @Id (minted by nextId(), never @GeneratedValue);
// founderId is a plain by-id column; status is stored by name; @Version drives optimistic locking.
// ownerIds and managers map to two @ElementCollection by-id side-tables (the owner/manager
// mutual-exclusion rule stays enforced in checkInvariants, not at the DB). The recursive
// purchasePolicy tree is stored as one JSON text column (PurchasePolicyJsonConverter). The
// discountPolicies are empty company-level stubs carrying no state, so they are @Transient. A
// protected no-arg ctor lets Hibernate hydrate; the public ctor still enforces the invariants.
@Entity
@Table(name = "companies")
public class ProductionCompany implements InvariantChecked {
    @Id
    private int companyId;
    /**
     * The original creator of the company. Immutable: the founder cannot be
     * removed from {@link #ownerIds} and cannot self-resign. The founder
     * always remains an owner — the two roles are coupled by design.
     */
    @Column(name = "founder_id", nullable = false)
    private int founderId;
    /**
     * All current owners (includes {@link #founderId} at all times). Any owner
     * can add or remove another owner; non-founder owners can also self-resign.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "company_owner_ids", joinColumns = @JoinColumn(name = "company_id"))
    @Column(name = "owner_id")
    private List<Integer> ownerIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus companyStatus;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column
    private Double rating;

    // Empty company-level policy stubs carry no state — not persisted; kept as an empty list.
    @Transient
    private List<DiscountPolicy> discountPolicies = new ArrayList<>();

    @Convert(converter = PurchasePolicyJsonConverter.class)
    @Column(name = "purchase_policy", columnDefinition = "text", nullable = false)
    private PurchasePolicy purchasePolicy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "company_managers", joinColumns = @JoinColumn(name = "company_id"))
    @Column(name = "manager_id")
    private List<Integer> managers;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected ProductionCompany() { }

    public ProductionCompany(int companyId, int founderId, String name, CompanyStatus companyStatus, String description, Double rating) {
        this.companyId = companyId;
        this.founderId = founderId;
        this.ownerIds = new ArrayList<>();
        this.ownerIds.add(founderId); // founder is the first owner
        this.name = name;
        this.description = description;
        this.rating = rating;
        this.companyStatus = companyStatus;
        this.discountPolicies = new ArrayList<>();
        this.purchasePolicy = new NoPurchasePolicy();
        this.managers = new ArrayList<>();
        checkInvariants();
    }

    public void addManager(int targetId) {
        if (managers.contains(targetId)) {
            throw new RuntimeException("The target user is already a manager of this company");
        }
        // Validate-before-commit: roll back the add if the target cannot be a manager
        // (e.g. they are already an owner), so a rejected call leaves managers unchanged.
        managers.add(targetId);
        try {
            checkInvariants();
        } catch (RuntimeException ex) {
            managers.remove(Integer.valueOf(targetId));
            throw ex;
        }
    }


    /**
     * Add a new owner. Caller (the {@code actorId}) must already be an owner.
     * No-op if {@code newOwnerId} is already an owner.
     *
     * @throws UnauthorizedActionException
     * if {@code actorId} is not an owner
     */
    public void addOwner(int actorId, int newOwnerId) {
        if (!isOwner(actorId)) {
            throw new UnauthorizedActionException("Only existing owners can add new owners");
        }
        if (this.ownerIds.contains(newOwnerId)) {
            return;
        }
        if (this.managers.contains(newOwnerId)) {
            // If the new owner is currently a manager, they should be removed from the managers list, upgrade.
            managers.remove(Integer.valueOf(newOwnerId));
        }
        this.ownerIds.add(newOwnerId);
        checkInvariants();
    }

    /**
     * Remove an owner. Caller must be an owner. The founder cannot be removed.
     * @throws UnauthorizedActionException
     * if {@code actorId} is not an owner.
     * @throws IllegalStateException                                                   
     * if {@code targetId} is the founder, or {@code targetId} is not an owner.
     */
    public void removeOwner(int actorId, int targetId) {
        if (!isOwner(actorId)) {
            throw new UnauthorizedActionException("Only owners can remove other owners");
        }
        if (targetId == this.founderId) {
            throw new IllegalStateException("The founder cannot be removed as owner");
        }
        if (!this.ownerIds.contains(targetId)) {
            throw new IllegalStateException("User is not an owner of this company");
        }
        this.ownerIds.remove(Integer.valueOf(targetId));
        checkInvariants();
    }

    /**
     * Self-resign as owner. The founder cannot resign (their two roles —
     * founder and owner — are immutably coupled).
     * @throws IllegalStateException if the caller is the founder or not an owner
     */
    public void resignAsOwner(int userId) {
        if (userId == this.founderId) {
            throw new IllegalStateException("The founder cannot resign as owner");
        }
        if (!this.ownerIds.contains(userId)) {
            throw new IllegalStateException("User is not an owner of this company");
        }
        this.ownerIds.remove(Integer.valueOf(userId));
        checkInvariants();
    }


    // general appointment revoke for manager or owner, whatever is active for the target user right now.
    public void RevokeAppointment(int targetId) {
        if (targetId == founderId) {
            throw new IllegalStateException("The founder cannot be revoked");
        }
        if (managers.contains(targetId)) {
            managers.remove(Integer.valueOf(targetId));
        } else if (ownerIds.contains(targetId)) {
            ownerIds.remove(Integer.valueOf(targetId));
        } else {
            throw new RuntimeException("User is not a manager or owner, cannot revoke appointment");
        }
        checkInvariants();
    }

    // ---------------------------------------------------------------------------
    // Skeleton additions — CompanyStatus lifecycle + cycle check + getters.
    // ---------------------------------------------------------------------------
    public List<Integer> getManagers() {
        // Defensive copy — external callers must not be able to mutate the internal
        // managers list (which would let them violate the owner/manager-overlap invariant).
        return new ArrayList<>(this.managers);
    }

    /**
     * Legacy accessor — returns the founder's id. Most call sites should
     * migrate to {@link #isOwner(int)} for permission checks or
     * {@link #getOwnerIds()} for the full owner list.
     */
    public int getOwnerId() {
        return this.founderId;
    }

    public List<Integer> getOwnersIds() {
        // Defensive copy — see getOwnerIds() for the canonical snapshot accessor.
        return new ArrayList<>(this.ownerIds);
    }

    /** Snapshot of all current owners (includes the founder). */
    public List<Integer> getOwnerIds() {
        return new ArrayList<>(this.ownerIds);
    }

    /** True iff {@code userId} is a current owner of this company. */
    public boolean isOwner(int userId) {
        return this.ownerIds.contains(userId);
    }

    

    public CompanyStatus getStatus() {
        return this.companyStatus;
    }

    public boolean isActive() {
        return this.companyStatus == CompanyStatus.ACTIVE;
    }

    /*
     * // II.4.13.x (Cancelled v0; defensive).
     * public void close(String reason) {
     * throw new
     * UnsupportedOperationException("II.4.13.x (Cancelled in v0): not implemented"
     * );
     * }
     * 
     * // II.4.14 (Cancelled v0; defensive).
     * public void reopen() {
     * throw new
     * UnsupportedOperationException("II.4.14 (Cancelled in v0): not implemented");
     * }
     */

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        // Validate-before-commit: roll back if the new name is blank/null, so a rejected
        // call never leaves the company with a corrupted name.
        String previous = this.name;
        this.name = name;
        try {
            checkInvariants();
        } catch (RuntimeException ex) {
            this.name = previous;
            throw ex;
        }
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
        checkInvariants();
    }

    public Double getRating() {
        return this.rating;
    }

    public void setRating(Double rating) {
        // Validate-before-commit: roll back if the new rating is out of range, so a rejected
        // call never leaves the company with a corrupted rating.
        Double previous = this.rating;
        this.rating = rating;
        try {
            checkInvariants();
        } catch (RuntimeException ex) {
            this.rating = previous;
            throw ex;
        }
    }

    // UC-18 — Founder is the original creator, immutable for the lifetime of the
    // company.
    public int getFounderId() {
        return founderId;
    }

    public int getCompanyId() {
        return companyId;
    }

    /**
     * Permission gate for owner-only actions. Now accepts <em>any</em> current
     * owner (per the multi-owner refactor) rather than founder-only.
     *
     * @throws UnauthorizedActionException if {@code ownerId2} is not a current
     *                                     owner
     */
    public void checkowner(int ownerId2) {
        if (!isOwner(ownerId2)) {
            throw new UnauthorizedActionException("Only an owner can perform this action");
        }
    }

    @Override
    public void checkInvariants() {
        if (companyId <= 0) {
            throw new IllegalStateException(
                    "ProductionCompany invariant violated: companyId must be positive (was " + companyId + ")");
        }
        if (founderId <= 0) {
            throw new IllegalStateException(
                    "ProductionCompany invariant violated: founderId must be positive (was " + founderId + ")");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("ProductionCompany invariant violated: name must be non-blank");
        }
        if (rating != null && (!Double.isFinite(rating) || rating < 0 || rating > 5)) {
            throw new IllegalStateException(
                    "ProductionCompany invariant violated: rating must be a finite value between 0 and 5 (was " + rating + ")");
        }
        if (companyStatus == null) {
            throw new IllegalStateException("ProductionCompany invariant violated: status must not be null");
        }
        if (discountPolicies == null) {
            throw new IllegalStateException(
                    "ProductionCompany invariant violated: discountPolicies list must not be null");
        }
       if (purchasePolicy == null) {
             throw new IllegalStateException("ProductionCompany invariant violated: purchasePolicy must not be null");
}
        if (managers == null) {
            throw new IllegalStateException("ProductionCompany invariant violated: managers map must not be null");
        }

        if (ownerIds == null || ownerIds.isEmpty()) {
            throw new IllegalStateException("ProductionCompany invariant violated: ownerIds list must be non-empty");
        }
        if (!ownerIds.contains(founderId)) {
            throw new IllegalStateException("ProductionCompany invariant violated: founder must always be in ownerIds");
        }
        // No owner can also be a manager (the roles are mutually exclusive)
        for (Integer ownerIdEntry : ownerIds) {
            if (managers.contains(ownerIdEntry)) {
                throw new IllegalStateException(
                        "ProductionCompany invariant violated: owner " + ownerIdEntry + " cannot also be a manager");
            }
        }
    }

    public PurchasePolicy getPurchasePolicy() {
    if (this.purchasePolicy == null) {
        this.purchasePolicy = new NoPurchasePolicy();
    }

    return this.purchasePolicy;
}

public void setPurchasePolicy(PurchasePolicy purchasePolicy) {
    if (purchasePolicy == null) {
        throw new IllegalArgumentException("Purchase policy cannot be null");
    }

    this.purchasePolicy = purchasePolicy;
    checkInvariants();
}

public void extendPurchasePolicy(PurchasePolicy additionalPolicy) {
    if (additionalPolicy == null) {
        throw new IllegalArgumentException("Additional purchase policy cannot be null");
    }

    if (this.purchasePolicy == null) {
        this.purchasePolicy = new NoPurchasePolicy();
    }

    this.purchasePolicy = new AndPurchasePolicy(
            this.purchasePolicy,
            additionalPolicy
    );
    checkInvariants();
}

}
