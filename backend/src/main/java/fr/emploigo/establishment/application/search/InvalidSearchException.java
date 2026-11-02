package fr.emploigo.establishment.application.search;

/** A search the list cannot run: a page out of bounds, a distance from no place. */
public class InvalidSearchException extends RuntimeException {
    public InvalidSearchException(String reason) {
        super(reason);
    }
}
