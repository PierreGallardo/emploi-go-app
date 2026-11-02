package fr.emploigo.config;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** An HTTP exchange with an external service that ends within a delay, response body included. */
public final class BoundedHttpExchange {
    private BoundedHttpExchange() {}

    /**
     * The response, or empty when the exchange did not end within {@code bound} or failed on the
     * network.
     */
    public static <T> Optional<HttpResponse<T>> send(
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> body,
            Duration bound)
            throws InterruptedException {
        // The request delay ends when the headers arrive, so a body that stops arriving would hold
        // the call for good.
        var exchange = client.sendAsync(request, body);
        try {
            return Optional.of(exchange.get(bound.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException error) {
            exchange.cancel(true);
            return Optional.empty();
        } catch (InterruptedException error) {
            exchange.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException) return Optional.empty();
            if (error.getCause() instanceof RuntimeException defect) throw defect;
            if (error.getCause() instanceof Error defect) throw defect;
            throw new IllegalStateException(error.getCause());
        }
    }
}
