package fr.emploigo.support;

import jakarta.servlet.http.Cookie;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;

/**
 * CSRF double-submit tokens, sent in the cookie and in the header as a browser does. Do not use
 * {@code SecurityMockMvcRequestPostProcessors.csrf()}: it replaces the token repository of the
 * filter, which every test of the context shares.
 *
 * <p>{@link #masked} is for tests that use a {@link Browser}, not MockMvc.
 */
public final class CsrfTokens {
    private static final String HEADER = "X-CSRF-TOKEN";
    private static final String TOKEN = "11111111-1111-4111-8111-111111111111";

    private CsrfTokens() {}

    public static RequestPostProcessor valid() {
        return request -> {
            withCookie(request, TOKEN);
            request.addHeader(HEADER, TOKEN);
            return request;
        };
    }

    public static RequestPostProcessor forged() {
        return request -> {
            withCookie(request, TOKEN);
            request.addHeader(HEADER, "22222222-2222-4222-8222-222222222222");
            return request;
        };
    }

    /**
     * {@code token} masked as {@code XorCsrfTokenRequestAttributeHandler} does; the fixed pad does
     * not change the answer.
     */
    public static String masked(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        byte[] masked = new byte[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            byte pad = (byte) (i + 1);
            masked[i] = pad;
            masked[bytes.length + i] = (byte) (pad ^ bytes[i]);
        }
        return Base64.getUrlEncoder().encodeToString(masked);
    }

    /** Keeps the cookies the request already carries, for example a session. */
    private static void withCookie(MockHttpServletRequest request, String token) {
        var cookies =
                new ArrayList<>(
                        Arrays.asList(
                                request.getCookies() == null
                                        ? new Cookie[0]
                                        : request.getCookies()));
        cookies.add(new Cookie(HostCookies.CSRF_COOKIE, token));
        request.setCookies(cookies.toArray(Cookie[]::new));
    }
}
