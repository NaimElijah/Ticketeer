package com.ticketing.system.shared.dto;

/**
 * Input to {@code CompanyManagementService.registerCompany} (UC-18). The service refuses a blank name
 * or description with {@code IllegalArgumentException}, and a name another company already uses with
 * {@code IllegalStateException}; it trims both before it stores them.
 */
public class CompanyRegistrationDTO {
    
    private final String name;
    private final String description;

    /**
     * Creates a registration request. Nothing is validated here; the service checks it.
     *
     * @param name        the company's name
     * @param description what the company is
     */
    public CompanyRegistrationDTO(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}