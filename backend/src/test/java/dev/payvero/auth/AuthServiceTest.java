package dev.payvero.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Interaction-level checks that do not need a database. Anything that depends
 * on real transaction or concurrency behaviour lives in the integration test,
 * because mocks would report success for all of it.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final String DUMMY_HASH = "$2a$10$dummy.hash.used.for.the.timing.defence";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // The constructor derives its dummy hash through the encoder.
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);

        JwtProperties properties = new JwtProperties(
                "secret-not-under-test-but-long-enough-for-hs256",
                Duration.ofMinutes(15),
                Duration.ofDays(7));

        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unknownEmailStillPaysForAPasswordComparison() {
        when(userRepository.findByEmail("ghost@payvero.dev")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost@payvero.dev", "hunter2"))
                .isInstanceOf(InvalidCredentialsException.class);

        // Without this the unknown-email path returns in microseconds while a
        // real address costs a full BCrypt, which is a usable enumeration oracle.
        verify(passwordEncoder).matches("hunter2", DUMMY_HASH);
    }

    @Test
    void unknownEmailAndWrongPasswordAreIndistinguishable() {
        when(userRepository.findByEmail("ghost@payvero.dev")).thenReturn(Optional.empty());
        User user = new User("carol@payvero.dev", "stored-hash", Role.USER);
        when(userRepository.findByEmail("carol@payvero.dev")).thenReturn(Optional.of(user));
        // Both comparisons are stubbed explicitly: once matches() has any
        // stubbing, an unstubbed argument combination is a strict-stubs
        // mismatch rather than a default return.
        when(passwordEncoder.matches("hunter2", DUMMY_HASH)).thenReturn(false);
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        Throwable unknownEmail = catchThrowable(() -> authService.login("ghost@payvero.dev", "hunter2"));
        Throwable wrongPassword = catchThrowable(() -> authService.login("carol@payvero.dev", "wrong"));

        assertThat(unknownEmail).isInstanceOf(InvalidCredentialsException.class);
        assertThat(wrongPassword).isInstanceOf(InvalidCredentialsException.class);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    @Test
    void loginNormalisesTheEmailBeforeLookup() {
        when(userRepository.findByEmail("alice@payvero.dev")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("  ALICE@PAYVERO.DEV  ", "pw"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository).findByEmail("alice@payvero.dev");
    }

    @Test
    void registerNormalisesTheEmailBeforePersisting() {
        when(userRepository.existsByEmail("bob@payvero.dev")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("access-token");

        authService.register("  BOB@PAYVERO.DEV ", "pw");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("bob@payvero.dev");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void registerRejectsAKnownEmailWithoutTouchingTheDatabase() {
        when(userRepository.existsByEmail("dupe@payvero.dev")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("dupe@payvero.dev", "pw"))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
