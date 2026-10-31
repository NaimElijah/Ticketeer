package com.ticketing.system.ui.support;

import com.ticketing.system.organization.application.dto.MembershipRole;

/**
 * The display label of each {@link MembershipRole}. Display text only: nothing may decide what a
 * member can do from a label, so rewording one here changes no capability.
 */
public final class MembershipRoleLabels {

    private MembershipRoleLabels() {
    }

    /**
     * @param role the member's role in a company
     * @return the label shown for {@code role}
     */
    public static String label(MembershipRole role) {
        return switch (role) {
            case FOUNDER -> "Founder";
            case CO_OWNER -> "Co-owner";
            case MANAGER -> "Manager";
        };
    }
}
