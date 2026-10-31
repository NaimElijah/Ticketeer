package com.ticketing.system.ui.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.ticketing.system.organization.application.dto.MembershipRole;

class MembershipRoleLabelsTest {

    @ParameterizedTest
    @CsvSource({"FOUNDER, Founder", "CO_OWNER, Co-owner", "MANAGER, Manager"})
    void GivenMembershipRole_WhenLabel_ThenItsDisplayLabel(MembershipRole role, String expectedLabel) {
        assertThat(MembershipRoleLabels.label(role)).isEqualTo(expectedLabel);
    }

    // The labels are display text only; they must still tell every role apart.
    @ParameterizedTest
    @EnumSource(MembershipRole.class)
    void GivenMembershipRole_WhenLabel_ThenNoOtherRoleSharesIt(MembershipRole role) {
        for (MembershipRole other : MembershipRole.values()) {
            if (other != role) {
                assertThat(MembershipRoleLabels.label(other)).isNotEqualTo(MembershipRoleLabels.label(role));
            }
        }
    }
}
