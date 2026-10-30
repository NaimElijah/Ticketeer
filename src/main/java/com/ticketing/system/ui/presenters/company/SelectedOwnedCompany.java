package com.ticketing.system.ui.presenters.company;

import java.util.List;
import java.util.Optional;

import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.ui.session.CurrentCompanies;

/**
 * Picks the company a workspace action applies to out of the caller's owned companies: the one
 * selected in the workspace ({@link CurrentCompanies}), which is the company the capability gate
 * checked. The owned list is unordered, so its first entry can be any of them and is never a default.
 * A selection the caller does not own counts as no company, not as a cue to pick another one, so an
 * action never lands in a company other than the one on screen. With nothing selected, an owner of
 * exactly one company gets that company, since there is nothing to choose.
 */
final class SelectedOwnedCompany {

    private SelectedOwnedCompany() { }

    /**
     * @param owned the companies the caller owns, from {@code findOwnedCompanies}
     * @return the selected company if the caller owns it; with nothing selected, the only owned
     *     company; otherwise empty
     */
    static Optional<ProductionCompanyDTO> among(List<ProductionCompanyDTO> owned) {
        Integer selectedId = CurrentCompanies.currentCompanyId();
        if (selectedId == null) {
            return owned.size() == 1 ? Optional.of(owned.get(0)) : Optional.empty();
        }
        return owned.stream().filter(company -> company.companyId() == selectedId).findFirst();
    }
}
