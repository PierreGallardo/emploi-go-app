package fr.emploigo.auth.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Stream;

class ResendAuthenticationMailSenderBodyTest {
    private static final ResendAuthenticationMailSender.MailBody MESSAGE =
            new ResendAuthenticationMailSender.MailBody(
                    "Emploi Go <ne-pas-repondre@emploi-go.test>",
                    List.of("jeune@example.test"),
                    "Ta demande",
                    "Bonjour");
    private static final String EXPECTED =
            "{\"from\":\"Emploi Go <ne-pas-repondre@emploi-go.test>\","
                    + "\"to\":[\"jeune@example.test\"],\"subject\":\"Ta demande\",\"text\":\"Bonjour\"}";

    @ParameterizedTest(name = "{argumentSetName}")
    @MethodSource("mappers")
    void bodyIsSerializedInTheFixedFieldOrderWhateverTheMapperSorting(ObjectMapper mapper) {
        var body = mapper.writeValueAsString(MESSAGE);

        assertThat(body).as("corps JSON").isEqualTo(EXPECTED);
    }

    static Stream<Arguments> mappers() {
        return Stream.of(
                argumentSet(
                        "tri alphabétique actif",
                        JsonMapper.builder()
                                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                                .build()),
                argumentSet(
                        "tri alphabétique inactif",
                        JsonMapper.builder()
                                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                                .build()));
    }
}
