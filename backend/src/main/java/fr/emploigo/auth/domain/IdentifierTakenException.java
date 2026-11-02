package fr.emploigo.auth.domain;

/**
 * An identifier of an account or of a Google identity is held by another row, which the database
 * arbitrated first.
 */
public class IdentifierTakenException extends RuntimeException {
    public enum Identifier {
        USERNAME,
        EMAIL,
        GOOGLE_SUBJECT,
        GOOGLE_ACCOUNT
    }

    private final Identifier identifier;

    public IdentifierTakenException(Identifier identifier) {
        super("Identifiant déjà pris : " + identifier.name(), null, false, false);
        this.identifier = identifier;
    }

    public Identifier identifier() {
        return identifier;
    }
}
