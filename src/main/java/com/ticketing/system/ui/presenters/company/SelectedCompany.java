package com.ticketing.system.ui.presenters.company;

import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

import com.ticketing.system.ui.session.CurrentCompanies;

/**
 * Picks the company a workspace action applies to out of the caller's companies: the one selected in
 * the workspace ({@link CurrentCompanies}), which is the company the capability gate checked. The
 * caller's company list is unordered, so its first entry can be any of them and is never a default.
 * A selection the caller does not belong to counts as no selection, not as a cue to pick another one,
 * so an action never lands in a company other than the one on screen. With nothing selected, a
 * caller with exactly one company gets that company, since there is nothing to choose.
 */
final class SelectedCompany {

    private SelectedCompany() { }

    /**
     * @param companies   the caller's companies, in whichever form the action reads them
     * @param companyIdOf the id of one of {@code companies}
     * @return the selected company if it is among {@code companies}; with nothing selected, the only
     *     company; otherwise empty
     */
    static <T> Optional<T> among(List<T> companies, ToIntFunction<T> companyIdOf) {
        return among(CurrentCompanies.currentCompanyId(), companies, companyIdOf);
    }

    /**
     * As {@link #among(List, ToIntFunction)}, for a caller that is handed the workspace's selection.
     *
     * @param selectedId the workspace's selected company id, or {@code null} for none
     */
    static <T> Optional<T> among(Integer selectedId, List<T> companies, ToIntFunction<T> companyIdOf) {
        if (selectedId == null) {
            return companies.size() == 1 ? Optional.of(companies.get(0)) : Optional.empty();
        }
        return companies.stream().filter(company -> companyIdOf.applyAsInt(company) == selectedId).findFirst();
    }
}
