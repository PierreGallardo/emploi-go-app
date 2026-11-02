package fr.emploigo.establishment.infrastructure;

import fr.emploigo.support.Containers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

/**
 * The roles of the API and of the worker with exactly the rights of {@code db/grants/map.sql}, read
 * from the script itself, so that the tests prove the rights production receives.
 */
public final class MapRoles {
    public static final String API = "map_api_test";
    public static final String WORKER = "map_worker_test";
    private static final String PASSWORD = "map-roles-test-only";

    private final JdbcTemplate administrator;
    private final List<LocalContainerEntityManagerFactoryBean> factories = new ArrayList<>();

    public MapRoles(JdbcTemplate administrator) {
        this.administrator = administrator;
    }

    /** Creates both roles anew and gives them the rights of the script. */
    public void create() {
        drop();
        for (String role : List.of(API, WORKER))
            administrator.execute("CREATE ROLE %s LOGIN PASSWORD '%s'".formatted(role, PASSWORD));
        String script =
                script().replaceAll("(?m)^--.*$", "")
                        .replace(":\"api\"", API)
                        .replace(":\"worker\"", WORKER);
        Arrays.stream(script.split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .forEach(administrator::execute);
    }

    /** A role belongs to the whole cluster: one left by a run that stopped halfway goes too. */
    public void drop() {
        factories.forEach(LocalContainerEntityManagerFactoryBean::destroy);
        factories.clear();
        for (String role : List.of(API, WORKER))
            administrator.execute(
                    """
                    DO $$ BEGIN
                        IF EXISTS (SELECT FROM pg_roles WHERE rolname = '%1$s') THEN
                            DROP OWNED BY %1$s;
                            DROP ROLE %1$s;
                        END IF;
                    END $$
                    """
                            .formatted(role));
    }

    public static DataSource connect(String role) {
        return new DriverManagerDataSource(Containers.postgres().getJdbcUrl(), role, PASSWORD);
    }

    /** JPA for one role, over the entities of one package, with its transactions. */
    public Jpa jpa(String role, String entitiesPackage) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(connect(role));
        factory.setPackagesToScan(entitiesPackage);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.afterPropertiesSet();
        factories.add(factory);
        EntityManagerFactory entities = factory.getObject();
        return new Jpa(
                SharedEntityManagerCreator.createSharedEntityManager(entities),
                new TransactionTemplate(new JpaTransactionManager(entities)));
    }

    public record Jpa(EntityManager entities, TransactionTemplate transactions) {}

    private static String script() {
        try {
            return new ClassPathResource("db/grants/map.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
