package fr.emploigo.establishment.domain;

/** Why an establishment of the directory is not shown, though kept for what refers to it. */
public enum ExclusionReason {
    /** Its activity has no sector: the allow list of the import leaves it out. */
    EXCLUDED_ACTIVITY,
    /** A company named after people, without sign nor trade name: the only name would be theirs. */
    NO_SIGN,
    /** A name, an address, a postal code or a commune the database cannot keep. */
    INCOMPLETE,
    /** Absent from a complete run of the import: closed, no longer an employer, or ceased. */
    NOT_FOUND
}
