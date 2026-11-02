package fr.emploigo.establishment.api.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.application.search.EstablishmentNotFoundException;
import fr.emploigo.establishment.application.search.InvalidSearchException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(
        assignableTypes = {
            EstablishmentController.class,
            PlaceController.class,
            DatasetsController.class
        })
@ApiOnly
public class SearchExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(SearchExceptionHandler.class);

    @ExceptionHandler(EstablishmentNotFoundException.class)
    ResponseEntity<SearchErrorResponse> notFound() {
        return response(
                404, "ESTABLISHMENT_NOT_FOUND", "Cet établissement n'est pas sur la carte.");
    }

    @ExceptionHandler({
        InvalidSearchException.class,
        BindException.class,
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<SearchErrorResponse> invalid() {
        return response(400, "INVALID_INPUT", "Vérifie la recherche.");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<SearchErrorResponse> unavailable(RuntimeException error) {
        LOG.error(
                "Lecture de la carte indisponible (type={}, cause={})",
                error.getClass().getSimpleName(),
                causeType(error));
        return response(
                503,
                "UNAVAILABLE",
                "La carte est temporairement indisponible. Réessaie plus tard.");
    }

    private ResponseEntity<SearchErrorResponse> response(int status, String code, String message) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new SearchErrorResponse(code, message));
    }

    private static String causeType(Throwable error) {
        for (int depth = 0; error.getCause() != null && depth < 16; depth++)
            error = error.getCause();
        return error.getClass().getSimpleName();
    }
}
