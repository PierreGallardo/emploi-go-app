package fr.emploigo.auth.application;

import fr.emploigo.auth.application.account.AccountRepository;
import fr.emploigo.auth.application.quota.AuthenticationRateLimiter;
import fr.emploigo.auth.application.quota.LoginAttemptLimiter;
import fr.emploigo.auth.application.session.AuthenticationSessions;
import fr.emploigo.auth.application.session.SessionPersistence;
import fr.emploigo.auth.domain.Account;
import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.auth.infrastructure.quota.RedisAuthenticationRateLimiter;
import fr.emploigo.auth.infrastructure.quota.TestQuotaKeys;
import fr.emploigo.support.TestClock;

import jakarta.servlet.http.Cookie;

import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.util.Assert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Creates accounts and persisted sessions for the tests of other modules, without the internals of
 * {@code auth}.
 */
public final class AuthenticationTestSupport {
    public static final String QUOTA_KEY = TestQuotaKeys.KEY;
    public static final String WITNESS_KEY = RedisAuthenticationRateLimiter.WITNESS_KEY;

    private final AccountRepository accounts;
    private final AuthenticationSessions sessions;
    private final SessionRepository<?> repository;
    private final CookieSerializer serializer;
    private final Clock clock;

    private AuthenticationTestSupport(ApplicationContext context) {
        accounts = context.getBean(AccountRepository.class);
        sessions = context.getBean(AuthenticationSessions.class);
        repository = context.getBean(SessionRepository.class);
        serializer = context.getBean(CookieSerializer.class);
        clock = context.getBean(Clock.class);
    }

    public static AuthenticationTestSupport of(ApplicationContext context) {
        return new AuthenticationTestSupport(context);
    }

    /** Moves the clock far ahead of earlier tests, and resets the spies of the doubles. */
    public static void resetBetweenTests(ApplicationContext context, TestClock clock) {
        clock.now = Instant.now().plus(Duration.ofDays(3650));
        Mockito.reset(
                context.getBean(PasswordEncoder.class),
                context.getBean(AuthenticationRateLimiter.class),
                context.getBean(LoginAttemptLimiter.class));
    }

    /** Written after {@code flushDb} in the same attempt: a flush that follows would erase it. */
    public static void reserveWitness(StringRedisTemplate redis) {
        redis.opsForValue().set(WITNESS_KEY, "1");
    }

    /**
     * Simulates the cooldown of the email flow {@code scope} (for example {@code
     * registration-mail}) expiring for {@code identity}, without sleeping or weakening application
     * configuration.
     */
    public static void permitNextMail(StringRedisTemplate redis, String scope, String identity) {
        redis.delete("auth:limits:" + scope + ":cooldown:" + TestQuotaKeys.KEYS.identity(identity));
    }

    /**
     * Opaque identity of an enabled account with a verified email and a live reconnection family.
     */
    public static final class Identity {
        private final SessionIdentity value;

        private Identity(SessionIdentity value) {
            this.value = value;
        }
    }

    public Identity newAccount(String prefix) {
        var alias = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var account =
                accounts.save(
                        new Account(
                                alias,
                                alias.toLowerCase(Locale.ROOT) + "@example.invalid",
                                "unused-fixture-hash",
                                true,
                                true));
        var grant = sessions.create(account.id(), SessionPersistence.REMEMBERED, null, null);
        return new Identity(
                new SessionIdentity(
                        grant.account().id(),
                        grant.account().credentialVersion(),
                        grant.account().familyId(),
                        clock.instant(),
                        true));
    }

    /** Persists a session with the given role, for example {@code "JEUNE"}. */
    public Cookie cookie(Identity identity, String role) {
        return cookie(repository, identity.value, role);
    }

    private <S extends Session> Cookie cookie(
            SessionRepository<S> repository, SessionIdentity identity, String role) {
        S session = repository.createSession();
        session.setAttribute(SessionIdentity.ATTRIBUTE, identity);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        Long.toString(identity.accountId()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        repository.save(session);
        var stored = repository.findById(session.getId());
        Assert.state(
                stored != null && identity.equals(stored.getAttribute(SessionIdentity.ATTRIBUTE)),
                "La preuve de session SQL doit être persistée.");
        Assert.state(
                stored.getAttribute("SPRING_SECURITY_CONTEXT") instanceof SecurityContext security
                        && security.getAuthentication().isAuthenticated(),
                "Le contexte authentifié doit être persisté.");

        // Use the context's real cookie name and encoding, including in MockMvc.
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        serializer.writeCookieValue(
                new CookieSerializer.CookieValue(request, response, session.getId()));
        var header = response.getHeader("Set-Cookie");
        Assert.state(header != null, "Le sérialiseur de session doit fournir un cookie.");
        var serialized = MockCookie.parse(header);
        var cookie = new Cookie(serialized.getName(), serialized.getValue());
        request.setCookies(cookie);
        Assert.state(
                serializer.readCookieValues(request).contains(session.getId()),
                "Le cookie doit résoudre la session SQL persistée.");
        return cookie;
    }
}
