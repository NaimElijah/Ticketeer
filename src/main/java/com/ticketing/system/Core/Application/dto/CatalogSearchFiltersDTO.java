package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.catalog.application.service.CatalogService;

import java.time.LocalDate;

// Input to CatalogService.searchGlobal() / searchByCompany() (UC-7).
// All fields nullable — represents an absent filter.
// Company-rating filters are used only for global search, not company-scoped search.
public record CatalogSearchFiltersDTO(
        String eventName,
        String artistName,
        String category,
        String keywords,
        Double minPrice,
        Double maxPrice,
        LocalDate fromDate,
        LocalDate toDate,
        String location,
        Double minEventRating,
        Double maxEventRating,
        Double minCompanyRating,
        Double maxCompanyRating
) {
    public static CatalogSearchFiltersDTO empty() {
        return new CatalogSearchFiltersDTO(
                null, null, null, null,
                null, null,
                null, null,
                null,
                null, null,
                null, null
        );
    }

    public CatalogSearchFiltersDTO normalized() {
        return new CatalogSearchFiltersDTO(
                blankToNull(eventName),
                blankToNull(artistName),
                blankToNull(category),
                blankToNull(keywords),
                minPrice,
                maxPrice,
                fromDate,
                toDate,
                blankToNull(location),
                minEventRating,
                maxEventRating,
                minCompanyRating,
                maxCompanyRating
        );
    }

    public CatalogSearchFiltersDTO withoutCompanyRating() {
        CatalogSearchFiltersDTO f = normalized();
        return new CatalogSearchFiltersDTO(
                f.eventName(),
                f.artistName(),
                f.category(),
                f.keywords(),
                f.minPrice(),
                f.maxPrice(),
                f.fromDate(),
                f.toDate(),
                f.location(),
                f.minEventRating(),
                f.maxEventRating(),
                null,
                null
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
