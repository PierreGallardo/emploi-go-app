package fr.emploigo.support;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Every cookie it receives, set or cleared, must follow the {@link HostCookies} rule. */
public class Browser {
    public final Map<String, String> cookies = new HashMap<>();

    /** The identifier of the last signup of this tab, which its code screen sends back. */
    public String registration;
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final int port;
    private final ObjectMapper json;

    public Browser(int port, ObjectMapper json) {
        this.port = port;
        this.json = json;
    }

    /** A second browser holding the same cookies, for example to replay a stolen session. */
    public Browser copy() {
        var copy = new Browser(port, json);
        copy.cookies.putAll(cookies);
        copy.registration = registration;
        return copy;
    }

    /**
     * The CSRF token this browser holds: like the frontend, it asks for the cookie only when it has
     * none.
     */
    public String csrf() throws Exception {
        if (!cookies.containsKey(HostCookies.CSRF_COOKIE)) {
            var response = get("csrf");
            assertThat(response.statusCode())
                    .as("status of the CSRF cookie request")
                    .isEqualTo(204);
        }
        return cookies.get(HostCookies.CSRF_COOKIE);
    }

    public HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    public HttpResponse<String> post(String path, Object body) throws Exception {
        return post(path, body, csrf());
    }

    public HttpResponse<String> post(String path, Object body, String csrf) throws Exception {
        return send(
                "POST",
                path,
                body instanceof String raw ? raw : json.writeValueAsString(body),
                csrf);
    }

    /**
     * The statuses of {@code times} identical posts, one after the other: each reads the CSRF token
     * again, as a reply may renew the cookie.
     */
    public List<Integer> postStatuses(String path, Object body, int times) throws Exception {
        var statuses = new ArrayList<Integer>();
        for (int i = 0; i < times; i++) statuses.add(post(path, body).statusCode());
        return statuses;
    }

    public HttpResponse<String> postForm(String path, String body, String csrf) throws Exception {
        return send("POST", path, body, csrf, "application/x-www-form-urlencoded");
    }

    public HttpResponse<String> send(String method, String path, String body, String csrf)
            throws Exception {
        return send(method, path, body, csrf, "application/json");
    }

    private HttpResponse<String> send(
            String method, String path, String body, String csrf, String contentType)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/" + path))
                        .timeout(Duration.ofSeconds(20));
        if (!cookies.isEmpty()) {
            request.header(
                    "Cookie",
                    cookies.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .collect(Collectors.joining("; ")));
        }
        if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", contentType);
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Registration-Id").ifPresent(id -> registration = id);
        for (String header : response.headers().allValues("set-cookie")) {
            HostCookies.assertHostOnly(header);
            String[] pair = header.split(";", 2)[0].split("=", 2);
            if (pair.length == 2 && !pair[1].isEmpty() && !header.contains("Max-Age=0"))
                cookies.put(pair[0], pair[1]);
            else cookies.remove(pair[0]);
        }
        return response;
    }
}
