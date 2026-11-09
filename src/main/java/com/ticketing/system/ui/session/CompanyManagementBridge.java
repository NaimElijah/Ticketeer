package com.ticketing.system.ui.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.organization.application.service.CompanyManagementService;

/**
 * Spring bridge so static helpers ({@link com.ticketing.system.ui.security.Capabilities})
 * can reach {@link CompanyManagementService} without dependency injection.
 */
@Component
public class CompanyManagementBridge {

    private static CompanyManagementService companyManagementService;

    /** Spring creates the bean; the constructor stores the service where {@link #service()} reads it. */
    @Autowired
    public CompanyManagementBridge(CompanyManagementService companyManagementService) {
        CompanyManagementBridge.companyManagementService = companyManagementService;
    }

    /** The service; {@code null} until Spring has created this bean. */
    public static CompanyManagementService service() {
        return companyManagementService;
    }
}
