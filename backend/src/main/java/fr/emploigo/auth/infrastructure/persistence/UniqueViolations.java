package fr.emploigo.auth.infrastructure.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

/** The unique violations that PostgreSQL reports for a failed write, read from its cause chain. */
public final class UniqueViolations {
    private static final String UNIQUE_VIOLATION = "23505";

    private UniqueViolations() {}

    /** Whether the write broke a unique constraint, whichever it is. */
    public static boolean occurred(DataIntegrityViolationException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState()))
                return true;
        }
        return false;
    }

    /** The name of the unique constraint that the write broke, empty for any other failure. */
    public static Optional<String> violatedConstraint(DataIntegrityViolationException error) {
        if (!occurred(error)) return Optional.empty();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException hibernate
                    && hibernate.getConstraintName() != null)
                return Optional.of(hibernate.getConstraintName());
        }
        return Optional.empty();
    }
}
