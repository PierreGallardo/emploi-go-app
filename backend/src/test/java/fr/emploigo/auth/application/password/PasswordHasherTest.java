package fr.emploigo.auth.application.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class PasswordHasherTest {
    private static final String PROPERTY = PasswordHasher.PROPERTY;
    private static final String HASH = "stored-hash";
    private static final String METER = "auth.password.hashing.rejections";

    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** The two computations of the cap: the same rules apply to both. */
    private enum Operation {
        MATCHES("matches") {
            @Override
            void call(PasswordHasher hashing, String raw) {
                hashing.matches(raw, HASH);
            }

            @Override
            void answer(PasswordEncoder encoder, Answer<?> answer) {
                when(encoder.matches(anyString(), anyString()))
                        .thenAnswer(
                                call -> {
                                    answer.answer(call);
                                    return false;
                                });
            }

            @Override
            void verifyComputed(PasswordEncoder encoder, String raw) {
                verify(encoder).matches(eq(raw), anyString());
            }

            @Override
            void verifyNotComputed(PasswordEncoder encoder, String raw) {
                verify(encoder, never()).matches(eq(raw), anyString());
            }
        },
        ENCODE("encode") {
            @Override
            void call(PasswordHasher hashing, String raw) {
                hashing.encode(raw);
            }

            @Override
            void answer(PasswordEncoder encoder, Answer<?> answer) {
                when(encoder.encode(anyString())).thenAnswer(answer);
            }

            @Override
            void verifyComputed(PasswordEncoder encoder, String raw) {
                verify(encoder).encode(raw);
            }

            @Override
            void verifyNotComputed(PasswordEncoder encoder, String raw) {
                verify(encoder, never()).encode(raw);
            }
        };

        final String tag;

        Operation(String tag) {
            this.tag = tag;
        }

        abstract void call(PasswordHasher hashing, String raw);

        abstract void answer(PasswordEncoder encoder, Answer<?> answer);

        abstract void verifyComputed(PasswordEncoder encoder, String raw);

        abstract void verifyNotComputed(PasswordEncoder encoder, String raw);
    }

    private PasswordHasher hashing(int limit) {
        return new PasswordHasher(
                encoder,
                meters,
                new MockEnvironment().withProperty(PROPERTY, Integer.toString(limit)));
    }

    private double rejections(Operation operation) {
        var counter = meters.find(METER).tag("operation", operation.tag).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void absentPropertyKeepsTheNumberOfProcessors() {
        var hashing = new PasswordHasher(encoder, meters, new MockEnvironment());

        assertThat(hashing.limit())
                .as("limit")
                .isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    static Stream<Arguments> acceptedLimits() {
        return Stream.of(
                argumentSet("minimum", "1", 1),
                argumentSet("valeur courante", "4", 4),
                argumentSet("maximum", "256", 256));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("acceptedLimits")
    void limitWithinTheBoundsIsAccepted(String value, int expected) {
        var environment = new MockEnvironment().withProperty(PROPERTY, value);

        var hashing = new PasswordHasher(encoder, meters, environment);

        assertThat(hashing.limit()).as("limit").isEqualTo(expected);
    }

    static Stream<Arguments> refusedLimits() {
        return Stream.of(
                argumentSet("zéro", "0"),
                argumentSet("négative", "-1"),
                argumentSet("au-dessus du maximum", "257"),
                argumentSet("texte quelconque", "beaucoup"));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("refusedLimits")
    void limitOutsideTheBoundsOrNotANumberIsRefusedAtStartup(String value) {
        var environment = new MockEnvironment().withProperty(PROPERTY, value);

        assertThatThrownBy(() -> new PasswordHasher(encoder, meters, environment))
                .as("startup")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void callBeyondTheLimitIsRefusedWithoutComputingAndCounted(Operation operation)
            throws Exception {
        var hashing = hashing(2);
        var entered = new CountDownLatch(2);
        var released = new CountDownLatch(1);
        operation.answer(
                encoder,
                call -> {
                    entered.countDown();
                    released.await(10, TimeUnit.SECONDS);
                    return null;
                });

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                IntStream.range(0, 2)
                        .forEach(
                                number ->
                                        pool.submit(
                                                () -> operation.call(hashing, "held-" + number)));
                assertThat(entered.await(10, TimeUnit.SECONDS))
                        .as("two computations in progress")
                        .isTrue();

                var failure =
                        catchThrowableOfType(
                                PasswordHashingBusyException.class,
                                () -> operation.call(hashing, "refused"));

                assertThat(failure).as("failure").isNotNull();
                operation.verifyNotComputed(encoder, "refused");
                assertThat(rejections(operation)).as("rejections").isEqualTo(1);
            } finally {
                released.countDown();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void permitIsReturnedWhenAComputationEnds(Operation operation) {
        var hashing = hashing(1);

        operation.call(hashing, "first");
        operation.call(hashing, "second");

        operation.verifyComputed(encoder, "second");
        assertThat(rejections(operation)).as("rejections").isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    void permitIsReturnedWhenTheEncoderFails(Operation operation) {
        var hashing = hashing(1);
        operation.answer(
                encoder,
                call -> {
                    throw new IllegalStateException("encodeur en échec");
                });

        assertThatThrownBy(() -> operation.call(hashing, "first"))
                .as("first computation")
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> operation.call(hashing, "second"))
                .as("second computation")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("encodeur en échec");
        assertThat(rejections(operation)).as("rejections").isZero();
    }

    @Test
    void capacityCheckPassesWhenAPermitIsFreeAndKeepsIt() {
        var hashing = hashing(1);

        assertThatCode(hashing::requireCapacity).as("check").doesNotThrowAnyException();
        assertThatCode(() -> hashing.encode("a password"))
                .as("computation after the check")
                .doesNotThrowAnyException();
    }

    @Test
    void capacityCheckIsRefusedAndCountedWhenEveryPermitIsTaken() throws Exception {
        var hashing = hashing(1);
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        Operation.ENCODE.answer(
                encoder,
                call -> {
                    entered.countDown();
                    released.await(10, TimeUnit.SECONDS);
                    return null;
                });

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                pool.submit(() -> hashing.encode("held"));
                assertThat(entered.await(10, TimeUnit.SECONDS))
                        .as("computation in progress")
                        .isTrue();

                var failure =
                        catchThrowableOfType(PasswordHashingBusyException.class, hashing::requireCapacity);

                assertThat(failure).as("failure").isNotNull();
                assertThat(rejections(Operation.ENCODE)).as("rejections").isEqualTo(1);
            } finally {
                released.countDown();
            }
        }
    }
}
