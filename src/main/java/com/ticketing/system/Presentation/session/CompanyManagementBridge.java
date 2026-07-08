package com.ticketing.system.Presentation.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.organization.application.service.CompanyManagementService;

/**
 * Spring bridge so static helpers ({@link com.ticketing.system.Presentation.security.Capabilities})
 * can reach {@link CompanyManagementService} without dependency injection.
 */
@Component
public class CompanyManagementBridge {

    private static CompanyManagementService companyManagementService;

    @Autowired
    public CompanyManagementBridge(CompanyManagementService companyManagementService) {
        CompanyManagementBridge.companyManagementService = companyManagementService;
    }

    public static CompanyManagementService service() {
        return companyManagementService;
    }
}
