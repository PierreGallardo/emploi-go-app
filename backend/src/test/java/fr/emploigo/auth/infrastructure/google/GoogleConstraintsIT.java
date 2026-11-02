package fr.emploigo.auth.infrastructure.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import fr.emploigo.auth.application.AuthenticationIntegrationTest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.stream.Stream;

class GoogleConstraintsIT extends AuthenticationIntegrationTest {
    private static final long UNKNOWN_ACCOUNT = 404;

    static Stream<Arguments> stepsOfAnAccount() {
        return Stream.of(
                argumentSet(
                        "auth_google_flows",
                        "auth_google_flows",
                        """
                        INSERT INTO auth_google_flows(
                            state_hash, browser_hash, account_id, credential_version, family_id,
                            expires_at)
                        VALUES ('state', 'browser', ?, 0, gen_random_uuid(), now())
                        """),
                argumentSet(
                        "auth_google_pending",
                        "auth_google_pending",
                        """
                        INSERT INTO auth_google_pending(
                            id, browser_hash, flow_hash, subject, email_key, authoritative_email,
                            account_id, credential_version, family_id, expires_at)
                        VALUES (gen_random_uuid(), 'browser', 'flow', 'subject',
                                'jeune@example.invalid', TRUE, ?, 0, gen_random_uuid(), now())
                        """));
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("stepsOfAnAccount")
    void theDatabaseRefusesAGoogleStepForAnUnknownAccount(String table, String insert) {
        assertThatThrownBy(() -> jdbc.update(insert, UNKNOWN_ACCOUNT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(table + "_account_id_fkey");
    }

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("stepsOfAnAccount")
    void deletingAnAccountDeletesItsGoogleSteps(String table, String insert) {
        long account =
                jdbc.queryForObject(
                        """
                        INSERT INTO auth_accounts(username, email_key, email_verified, enabled)
                        VALUES ('jeune', 'jeune@example.invalid', TRUE, TRUE)
                        RETURNING id
                        """,
                        Long.class);
        jdbc.update(insert, account);

        jdbc.update("DELETE FROM auth_accounts WHERE id = ?", account);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
    }
}
