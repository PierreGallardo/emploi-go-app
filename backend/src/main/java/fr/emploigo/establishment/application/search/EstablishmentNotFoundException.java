package fr.emploigo.establishment.application.search;

/** The establishment is not on the map: unknown, hidden or excluded. */
public class EstablishmentNotFoundException extends RuntimeException {
    public EstablishmentNotFoundException() {
        super("Établissement absent de la carte.");
    }
}
