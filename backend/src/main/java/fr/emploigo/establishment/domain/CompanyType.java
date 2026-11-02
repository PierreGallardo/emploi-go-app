package fr.emploigo.establishment.domain;

import java.util.Set;

/** What kind of employer an establishment is, derived from its activity. */
public enum CompanyType {
    COMPANY,
    TEMP_AGENCY,
    RECRUITMENT,
    TRAINING,
    IT_SERVICES;

    private static final Set<ActivityCode> TEMP_AGENCIES =
            Set.of(
                    new ActivityCode(Nomenclature.NAF_REV2, "78.20Z"),
                    new ActivityCode(Nomenclature.NAF_2025, "78.20G"));
    private static final Set<ActivityCode> RECRUITERS =
            Set.of(
                    new ActivityCode(Nomenclature.NAF_REV2, "78.10Z"),
                    new ActivityCode(Nomenclature.NAF_2025, "78.10Y"));
    // Programming and IT consultancy: an approximation of the digital services companies.
    private static final Set<ActivityCode> IT_SERVICE_COMPANIES =
            Set.of(
                    new ActivityCode(Nomenclature.NAF_REV2, "62.01Z"),
                    new ActivityCode(Nomenclature.NAF_REV2, "62.02A"),
                    new ActivityCode(Nomenclature.NAF_2025, "62.10Y"),
                    new ActivityCode(Nomenclature.NAF_2025, "62.20G"));

    /**
     * The type of an establishment; {@code trainingOrganization} says that the establishment is a
     * declared training organisation.
     */
    public static CompanyType of(ActivityCode activity, boolean trainingOrganization) {
        if (TEMP_AGENCIES.contains(activity)) return TEMP_AGENCY;
        if (RECRUITERS.contains(activity)) return RECRUITMENT;
        if (IT_SERVICE_COMPANIES.contains(activity)) return IT_SERVICES;
        if (activity.code().startsWith("85.") || trainingOrganization) return TRAINING;
        return COMPANY;
    }
}
