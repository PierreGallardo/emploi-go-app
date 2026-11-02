package fr.emploigo.establishment.domain;

/** What the import does with an establishment of the directory. */
public sealed interface Assessment {
    /** Shown on the map once we make it visible. */
    record Retained(Establishment establishment) implements Assessment {}

    /** Never added; hidden with its reason if it was retained before. */
    record Excluded(Siret siret, ExclusionReason reason) implements Assessment {}

    /**
     * The company or the person opposed the diffusion of the data: nothing of the establishment is
     * kept.
     */
    record Withheld(Siret siret) implements Assessment {}
}
