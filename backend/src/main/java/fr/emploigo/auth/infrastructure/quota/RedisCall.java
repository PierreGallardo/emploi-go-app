package fr.emploigo.auth.infrastructure.quota;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One call to Redis for the counters. An error or a delay that ends the call goes to {@code
 * onFailure}, and the result is empty: the caller then answers that the counters did not answer.
 */
public final class RedisCall {
    private RedisCall() {}

    public static <T> Optional<T> run(Supplier<T> call, Consumer<RuntimeException> onFailure) {
        try {
            return Optional.of(call.get());
        } catch (RuntimeException error) {
            onFailure.accept(error);
            return Optional.empty();
        }
    }
}
