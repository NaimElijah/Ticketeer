package com.ticketing.system.ui.presenters.company;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for {@code CompanyRegistrationView}: registers a new production company for the signed-in
 * member and makes it the workspace's selected company. Holds no Vaadin imports; it returns a typed
 * outcome and reads the token itself from {@code AuthSession}.
 */
@Component
public class CompanyRegistrationPresenter {

    private final CompanyManagementService companyManagementService;

    /** Creates the presenter over the company management service. */
    public CompanyRegistrationPresenter(CompanyManagementService companyManagementService) {
        this.companyManagementService = companyManagementService;
    }

    /**
     * Registers a company with the signed-in member as its founder. On success the new company becomes
     * the workspace's selected company.
     *
     * @param name        the company name as typed
     * @param description the description as typed
     * @return {@link Outcome.NotAuthenticated} when no token is stored or the service rejects it;
     *     {@link Outcome.InvalidInput} when the service refuses an argument;
     *     {@link Outcome.NameTaken} when a company already has the name; {@link Outcome.Failure}
     *     with {@code ServiceErrors}' text for any other failure
     */
    public Outcome register(String name, String description) {
        String token = AuthSession.token();
        if (token == null || token.isBlank()) {
            return new Outcome.NotAuthenticated();
        }

        try {
            ProductionCompanyDTO company = companyManagementService.registerCompany(
                    token,
                    new CompanyRegistrationDTO(name, description));
            CurrentCompanies.setCurrentCompany(company.companyId());
            return new Outcome.Success(company);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (IllegalArgumentException e) {
            return new Outcome.InvalidInput(ServiceErrors.userMessage(e));
        } catch (IllegalStateException e) {
            // A taken name comes as a plain IllegalStateException, told apart only by its text (until #219).
            if (CompanyManagementService.COMPANY_NAME_TAKEN_MESSAGE.equals(e.getMessage())) {
                return new Outcome.NameTaken(e.getMessage());
            }
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome of {@link #register}, which the view switches on. */
    public sealed interface Outcome {
        /**
         * The company is registered.
         *
         * @param company the new company
         */
        record Success(ProductionCompanyDTO company) implements Outcome { }

        /** No token is stored, or the service rejected it as invalid. */
        record NotAuthenticated() implements Outcome { }

        /**
         * The service refused an argument ({@code IllegalArgumentException}).
         *
         * @param reason the exception's message, which reaches the user
         */
        record InvalidInput(String reason) implements Outcome { }

        /**
         * Another company already has the name.
         *
         * @param reason the service's message, which reaches the user
         */
        record NameTaken(String reason) implements Outcome { }

        /**
         * Any other failure.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }
}
