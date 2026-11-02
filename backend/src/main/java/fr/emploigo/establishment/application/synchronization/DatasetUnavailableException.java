package fr.emploigo.establishment.application.synchronization;

/** An open data file could not be read whole: the data in place are kept. */
public class DatasetUnavailableException extends RuntimeException {
    public DatasetUnavailableException(String dataset, String cause) {
        super("Jeu de données indisponible (" + dataset + ") : " + cause);
    }
}
