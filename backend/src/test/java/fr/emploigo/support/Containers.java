package fr.emploigo.support;

import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** PostgreSQL and Redis shared by every integration test of the JVM, started on first use. */
public final class Containers {
    public static final String REDIS_PASSWORD = "testcontainers-local-only";

    /**
     * Whether a test limited the memory of Redis: only then is it worth a command to lift the
     * limit.
     */
    private static volatile boolean memoryLimited;

    private Containers() {}

    public static PostgreSQLContainer postgres() {
        return PostgresHolder.INSTANCE;
    }

    public static GenericContainer<?> redis() {
        return RedisHolder.INSTANCE;
    }

    /**
     * Pauses Redis until the handle is closed: use it in a try-with-resources, {@link
     * IntegrationTest} also resumes Redis after each test.
     */
    public static RedisPause pauseRedis() {
        client().pauseContainerCmd(redis().getContainerId()).exec();
        return new RedisPause();
    }

    /**
     * Makes Redis refuse writes as a full memory does (noeviction) until the handle is closed: use
     * it in a try-with-resources, {@link IntegrationTest} also lifts the limit after each test.
     */
    public static RedisMemoryFull fillRedisMemory() {
        memoryLimited = true;
        redisMemory("1");
        return new RedisMemoryFull();
    }

    /** Restarts the server in its container: only what the disk persistence kept survives. */
    public static void restartRedis() {
        String before = redisRunId();
        // DEBUG RESTART closes the connection that sent it: the command has no reply, and its exit
        // code means nothing.
        redisCli("debug", "restart");
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .ignoreExceptions()
                .until(() -> !redisRunId().equals(before));
    }

    /**
     * Makes Redis answer nothing for {@code duration}: returns at once, before the server is known
     * to be blocked.
     */
    public static void stallRedis(Duration duration) {
        Thread.ofVirtual()
                .start(() -> redisCli("debug", "sleep", Long.toString(duration.toSeconds())));
    }

    /**
     * The {@code run_id} of the running server: it changes with every start, so it tells a restart
     * that happened.
     */
    public static String redisRunId() {
        return redisCli("info", "server")
                .getStdout()
                .lines()
                .filter(line -> line.startsWith("run_id:"))
                .findFirst()
                .map(line -> line.substring("run_id:".length()).trim())
                .orElseThrow();
    }

    /** Restarts Redis empty, as after the loss of its volume. */
    public static void restartRedisEmpty() {
        redisCli("flushall");
        restartRedis();
    }

    private static Container.ExecResult redisCli(String... command) {
        var arguments =
                new ArrayList<>(List.of("redis-cli", "-a", REDIS_PASSWORD, "--no-auth-warning"));
        arguments.addAll(List.of(command));
        try {
            return redis().execInContainer(arguments.toArray(String[]::new));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    static void ensureRedisMemoryFree() {
        if (!memoryLimited) return;
        redisMemory("0");
        memoryLimited = false;
    }

    private static void redisMemory(String bytes) {
        try {
            var result =
                    redis().execInContainer(
                                    "redis-cli",
                                    "-a",
                                    REDIS_PASSWORD,
                                    "--no-auth-warning",
                                    "config",
                                    "set",
                                    "maxmemory",
                                    bytes);
            if (result.getExitCode() != 0)
                throw new IllegalStateException("CONFIG SET maxmemory : " + result.getStderr());
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    static void ensureRedisRunning() {
        if (isRedisPaused()) {
            client().unpauseContainerCmd(redis().getContainerId()).exec();
        }
    }

    private static boolean isRedisPaused() {
        var state = client().inspectContainerCmd(redis().getContainerId()).exec().getState();
        return Boolean.TRUE.equals(state.getPaused());
    }

    private static DockerClient client() {
        return redis().getDockerClient();
    }

    public static final class RedisMemoryFull implements AutoCloseable {
        private RedisMemoryFull() {}

        @Override
        public void close() {
            ensureRedisMemoryFree();
        }
    }

    public static final class RedisPause implements AutoCloseable {
        private RedisPause() {}

        @Override
        public void close() {
            ensureRedisRunning();
        }
    }

    private static final class PostgresHolder {
        // The image of backend/compose.yml, built from the same Dockerfile.
        private static final DockerImageName IMAGE =
                DockerImageName.parse(
                                new ImageFromDockerfile("emploi-go/postgres", false)
                                        .withDockerfile(Path.of("postgres", "Dockerfile"))
                                        .get())
                        .asCompatibleSubstituteFor("postgres");

        // Disposable PostgreSQL in memory: real transactions without host-disk initdb stalls.
        static final PostgreSQLContainer INSTANCE =
                new PostgreSQLContainer(IMAGE)
                        .withTmpFs(Map.of("/var/lib/postgresql", "rw,size=512m"));

        static {
            INSTANCE.start();
        }
    }

    private static final class RedisHolder {
        // Production shape (password, noeviction, AOF of backend/compose.yml) without RDB snapshot:
        // only the AOF may restore the counters.
        static final GenericContainer<?> INSTANCE =
                new GenericContainer<>(DockerImageName.parse("redis:8.10.2"))
                        // DEBUG RESTART runs argv[0] from /data, hence the absolute path; never in
                        // a Compose file or production configuration.
                        .withCommand(
                                "/usr/local/bin/redis-server",
                                "--requirepass",
                                REDIS_PASSWORD,
                                "--maxmemory-policy",
                                "noeviction",
                                "--appendonly",
                                "yes",
                                "--appendfsync",
                                "everysec",
                                "--save",
                                "",
                                "--enable-debug-command",
                                "yes")
                        .withExposedPorts(6379);

        static {
            INSTANCE.start();
        }
    }
}
