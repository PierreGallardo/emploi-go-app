package fr.emploigo.auth.api;

import static fr.emploigo.support.FakeGoogle.CODES;

import static org.assertj.core.api.Assertions.assertThat;

import fr.emploigo.support.Browser;
import fr.emploigo.support.FakeGoogle.Authorization;
import fr.emploigo.support.FakeGoogle;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A browser that follows the Google flow: it remembers the current flow identifier of the page. */
public class GoogleBrowser extends Browser {
    /**
     * The parameters of an authorization request, as the browser would carry them to Google and
     * back.
     */
    public record Flow(Map<String, String> parameters) {
        @Override
        public String toString() {
            return "Flow[redacted]";
        }
    }

    private final ObjectMapper json;
    private String flowId;

    public GoogleBrowser(int port, ObjectMapper json) {
        super(port, json);
        this.json = json;
    }

    @Override
    public HttpResponse<String> get(String path) throws Exception {
        var response = super.get(path);
        if (path.equals("google/status") && response.statusCode() == 200) {
            var status = json.readTree(response.body());
            if (status.path("pending").asBoolean()) flowId = status.path("flowId").asText();
        }
        return response;
    }

    @Override
    public HttpResponse<String> post(String path, Object body) throws Exception {
        if (List.of("google/signup", "google/access", "google/link", "google/email").contains(path)
                && body instanceof Map<?, ?> values) {
            var filled = new HashMap<String, Object>();
            values.forEach((key, value) -> filled.put(key.toString(), value));
            if (!values.containsKey("flowId")) {
                get("google/status");
                filled.put("flowId", flowId != null ? flowId : UUID.randomUUID().toString());
            }
            // The page checks the box of the terms before it creates an account.
            if (path.equals("google/signup")) filled.putIfAbsent("acceptedTerms", true);
            body = filled;
        }
        return super.post(path, body);
    }

    public Flow start() throws Exception {
        var result = post("google/start", "");
        assertThat(result.statusCode()).as("start status").isEqualTo(200);
        var uri = URI.create(json.readTree(result.body()).path("authorizationUri").asText());
        return new Flow(FakeGoogle.parameters(uri.getRawQuery()));
    }

    /**
     * The redirect of Google to the application, with a fresh authorization code (or a
     * cancellation).
     */
    public HttpResponse<String> callback(Flow flow, boolean cancel) throws Exception {
        String code = UUID.randomUUID().toString();
        CODES.put(
                code,
                new Authorization(
                        flow.parameters().get("nonce"),
                        flow.parameters().get("code_challenge"),
                        flow.parameters().get("redirect_uri")));
        return get(
                "google/callback/google?state="
                        + URLEncoder.encode(flow.parameters().get("state"), StandardCharsets.UTF_8)
                        + (cancel ? "&error=access_denied" : "&code=" + code));
    }

    public JsonNode status() throws Exception {
        return json.readTree(get("google/status").body());
    }

    public String stage() throws Exception {
        return status().path("stage").asText();
    }

    public String errorCode() throws Exception {
        return status().path("errorCode").asText();
    }

    public boolean pending() throws Exception {
        return status().path("pending").asBoolean();
    }

    public String username() throws Exception {
        return json.readTree(get("session").body()).path("username").asText();
    }
}
