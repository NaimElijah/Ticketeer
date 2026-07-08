package com.ticketing.system.Core.Domain.messaging;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.domain.User;

// What kind of entity is sending or receiving in a Conversation.
//   MEMBER      - a specific User (memberId)
//   COMPANY     - a specific ProductionCompany (companyId)
//   ADMIN       - a specific System Admin (adminId)
//   ADMIN_GROUP - any System Admin (complaint queue)
//   SYSTEM      - the platform itself (system-generated messages)
public enum ParticipantType {
    MEMBER,
    COMPANY,
    ADMIN,
    ADMIN_GROUP,
    SYSTEM
}
