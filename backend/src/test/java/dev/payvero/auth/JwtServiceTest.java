package dev.payvero.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long-for-hs256";
    private static final String OTHER_SECRET = "different-secret-key-also-at-least-32-bytes-long";

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    @Test
    void generatedTokenRoundTripsEveryClaim() {
        UUID userId = UUID.randomUUID();
        User user = userWithId(userId, "alice@payvero.dev", Role.ADMIN);

        String token = serviceAt(NOW).generateAccessToken(user);
        Claims claims = serviceAt(NOW).parseToken(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("alice@payvero.dev");
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
        assertThat(claims.getIssuedAt()).isEqualTo(Date.from(NOW));
        assertThat(claims.getExpiration()).isEqualTo(Date.from(NOW.plus(ACCESS_TTL)));
    }

    @Test
    void extractHelpersReturnUserIdAndRole() {
        UUID userId = UUID.randomUUID();
        User user = userWithId(userId, "bob@payvero.dev", Role.USER);
        JwtService service = serviceAt(NOW);

        String token = service.generateAccessToken(user);

        assertThat(service.extractUserId(token)).isEqualTo(userId);
        assertThat(service.extractRole(token)).isEqualTo(Role.USER);
    }

    @Test
    void tokenIsValidOneSecondBeforeExpiry() {
        User user = userWithId(UUID.randomUUID(), "carol@payvero.dev", Role.USER);
        String token = serviceAt(NOW).generateAccessToken(user);

        JwtService justBeforeExpiry = serviceAt(NOW.plus(ACCESS_TTL).minusSeconds(1));

        assertThat(justBeforeExpiry.isValid(token)).isTrue();
    }

    @Test
    void tokenIsRejectedOnceTheClockPassesExpiry() {
        User user = userWithId(UUID.randomUUID(), "dave@payvero.dev", Role.USER);
        String token = serviceAt(NOW).generateAccessToken(user);

        JwtService afterExpiry = serviceAt(NOW.plus(ACCESS_TTL).plusSeconds(1));

        assertThat(afterExpiry.isValid(token)).isFalse();
        assertThatThrownBy(() -> afterExpiry.parseToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenWithTamperedSignatureIsRejected() {
        User user = userWithId(UUID.randomUUID(), "erin@payvero.dev", Role.USER);
        JwtService service = serviceAt(NOW);
        String tampered = tamperSignature(service.generateAccessToken(user));

        assertThat(service.isValid(tampered)).isFalse();
        assertThatThrownBy(() -> service.parseToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        User user = userWithId(UUID.randomUUID(), "frank@payvero.dev", Role.USER);
        String token = serviceAt(NOW, SECRET).generateAccessToken(user);

        JwtService otherKeyService = serviceAt(NOW, OTHER_SECRET);

        assertThat(otherKeyService.isValid(token)).isFalse();
        assertThatThrownBy(() -> otherKeyService.parseToken(token))
                .isInstanceOf(JwtException.class);
    }

    private static JwtService serviceAt(Instant instant) {
        return serviceAt(instant, SECRET);
    }

    private static JwtService serviceAt(Instant instant, String secret) {
        JwtProperties properties = new JwtProperties(secret, ACCESS_TTL, REFRESH_TTL);
        return new JwtService(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }

    /**
     * The id is database-generated, so it is null on a plain constructed entity.
     * Set it reflectively rather than widening the entity's API for a test.
     */
    private static User userWithId(UUID id, String email, Role role) {
        User user = new User(email, "bcrypt-hash-not-under-test", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static String tamperSignature(String token) {
        int lastDot = token.lastIndexOf('.');
        String signature = token.substring(lastDot + 1);
        char last = signature.charAt(signature.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return token.substring(0, lastDot + 1)
                + signature.substring(0, signature.length() - 1)
                + replacement;
    }
}
