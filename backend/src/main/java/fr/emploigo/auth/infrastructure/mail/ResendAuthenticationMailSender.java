package fr.emploigo.auth.infrastructure.mail;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import fr.emploigo.auth.application.mail.AuthenticationMailSender;
import fr.emploigo.config.ApiOnly;
import fr.emploigo.config.BoundedHttpExchange;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/** Shared direct transport. Provider bodies and recipients never enter diagnostics. */
@Component
@ApiOnly
public class ResendAuthenticationMailSender implements AuthenticationMailSender {
    /** Field order matters: one idempotency key requires a byte-identical body. */
    @JsonPropertyOrder({"from", "to", "subject", "text"})
    record MailBody(String from, List<String> to, String subject, String text) {}

    private final AuthenticationMailConfiguration configuration;
    private final ObjectMapper json;
    private final HttpClient client;

    public ResendAuthenticationMailSender(
            AuthenticationMailConfiguration configuration, ObjectMapper json) {
        this.configuration = configuration;
        this.json = json;
        this.client =
                HttpClient.newBuilder().connectTimeout(configuration.mailConnectTimeout()).build();
    }

    @Override
    public Result send(
            String idempotencyKey, String recipient, String sender, String subject, String text) {
        if (!configuration.enabled()) return Result.FAILED;
        try {
            var request =
                    HttpRequest.newBuilder(configuration.endpoint())
                            .timeout(configuration.mailResponseTimeout())
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", idempotencyKey);
            if (!configuration.apiKey().isBlank())
                request.header("Authorization", "Bearer " + configuration.apiKey());
            // Both delays bound the whole exchange, body included.
            var answer =
                    BoundedHttpExchange.send(
                            client,
                            request.POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    json.writeValueAsString(
                                                            new MailBody(
                                                                    sender,
                                                                    List.of(recipient),
                                                                    subject,
                                                                    text))))
                                    .build(),
                            HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.ofString(), 16_384),
                            configuration
                                    .mailConnectTimeout()
                                    .plus(configuration.mailResponseTimeout()));
            if (answer.isEmpty()) return Result.UNCERTAIN;
            HttpResponse<String> response = answer.get();
            if (response.statusCode() == 200
                    && json.readTree(response.body()).path("id").isTextual()
                    && !json.readTree(response.body()).path("id").asText().isBlank())
                return Result.SENT;
            if (response.statusCode() == 429 || response.statusCode() >= 500)
                return Result.UNCERTAIN;
            if (response.statusCode() == 409
                    && json.readTree(response.body())
                            .path("name")
                            .asText()
                            .equals("concurrent_idempotent_requests")) return Result.UNCERTAIN;
            return Result.FAILED;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.UNCERTAIN;
        } catch (tools.jackson.core.JacksonException error) {
            return Result.UNCERTAIN;
        }
    }
}
