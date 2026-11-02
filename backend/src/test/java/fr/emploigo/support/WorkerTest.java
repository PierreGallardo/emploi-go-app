package fr.emploigo.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The context of the worker, one for every test that uses it: the scheduler looks for due tasks
 * once an hour, and the directory of the establishments and the open data files point to a closed
 * local port, so no test calls them: the first imports that the worker starts fail at once.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "spring.data.redis.host=127.0.0.1",
            "spring.data.redis.port=1",
            "db-scheduler.polling-interval=1h",
            "emploi-go.establishment-synchronization.directory-url=http://127.0.0.1:1",
            "emploi-go.dataset-synchronization.transit-stops.url=http://127.0.0.1:1/gtfs.zip",
            "emploi-go.dataset-synchronization.addresses.url=http://127.0.0.1:1/adresses.csv.gz",
            "emploi-go.dataset-synchronization.training-organizations.url=http://127.0.0.1:1/of.csv"
        })
@ActiveProfiles({"local", "worker"})
@Import({PostgresConfiguration.class, SchedulerFixtures.RoundTripTask.class})
public @interface WorkerTest {}
