package fr.emploigo.establishment.application.synchronization;

/** The directory did not answer a page: the run stops, keeping what it wrote. */
public class DirectoryUnavailableException extends RuntimeException {
    public DirectoryUnavailableException(int page, String cause) {
        super("Annuaire Sirene indisponible : " + cause + " (page " + page + ")");
    }
}
