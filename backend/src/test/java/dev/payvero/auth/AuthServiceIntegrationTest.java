package dev.payvero.auth;

import dev.payvero.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deliberately not annotated {@code @Transactional}. A transactional test would
 * make AuthService join the test's transaction instead of opening its own, so
 * {@code noRollbackFor} would no longer govern the commit boundary and nothing
 * would ever be committed for a later read to observe. Every assertion about
 * revocation surviving an exception depends on real commits, so state is torn
 * down explicitly instead of rolled back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
class AuthServiceIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void familyRevocationSurvivesTheReuseException() {
        TokenPair initial = authService.register("victim@payvero.dev", "correct horse battery");
        UUID familyId = familyOf(initial.refreshToken());

        authService.refresh(initial.refreshToken());

        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .isInstanceOf(RefreshTokenReuseException.class);

        // Re-read after the exception. Against a plain @Transactional without
        // noRollbackFor the throw would have rolled the revocation back and
        // these rows would still be live, while a throw-only assertion passed.
        List<RefreshToken> family = refreshTokenRepository.findAllByFamilyId(familyId);
        assertThat(family).hasSize(2);
        assertThat(family).allSatisfy(token -> assertThat(token.isRevoked()).isTrue());
    }

    @Test
    void revokeIfActiveBehavesAsACompareAndSwap() {
        User user = userRepository.saveAndFlush(new User("cas@payvero.dev", "hash", Role.USER));
        RefreshToken token = refreshTokenRepository.saveAndFlush(new RefreshToken(
                user, "cas-token-hash", UUID.randomUUID(), clock.instant().plus(1, ChronoUnit.DAYS)));

        Instant now = clock.instant();

        // Each call gets its own transaction, both because @Modifying with
        // flushAutomatically requires one and because that is what two separate
        // callers would actually look like.
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // Deterministic proof of the guard the whole rotation design rests on:
        // the first call claims the row, the second finds nothing left to claim.
        Integer firstClaim = transaction.execute(status ->
                refreshTokenRepository.revokeIfActive(token.getId(), now));
        Integer secondClaim = transaction.execute(status ->
                refreshTokenRepository.revokeIfActive(token.getId(), now));

        assertThat(firstClaim).isEqualTo(1);
        assertThat(secondClaim).isEqualTo(0);
    }

    @Test
    void concurrentRefreshLetsExactlyOneCallerWin() throws Exception {
        TokenPair initial = authService.register("racer@payvero.dev", "correct horse battery");
        UUID familyId = familyOf(initial.refreshToken());

        int threads = 8;
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    release.await();
                    try {
                        authService.refresh(initial.refreshToken());
                        return Outcome.WON;
                    } catch (RefreshTokenReuseException e) {
                        return Outcome.REJECTED_AS_REUSE;
                    } catch (DataIntegrityViolationException e) {
                        return Outcome.LEAKED_PERSISTENCE_EXCEPTION;
                    }
                }));
            }
            release.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            // This does not guarantee the rotated == 0 branch executed on any
            // given run: a loser that arrives after the winner commits takes the
            // already-revoked branch instead. revokeIfActiveBehavesAsACompareAndSwap
            // pins that branch deterministically. What this proves is the
            // invariant that matters, that concurrency never mints two live
            // successors from one token.
            assertThat(outcomes).filteredOn(Outcome.WON::equals).hasSize(1);
            assertThat(outcomes).doesNotContain(Outcome.LEAKED_PERSISTENCE_EXCEPTION);
            assertThat(outcomes).filteredOn(Outcome.REJECTED_AS_REUSE::equals).hasSize(threads - 1);
        } finally {
            pool.shutdownNow();
        }

        // The successor the winner issued is revoked too: a suspected theft
        // kills the whole lineage, including tokens minted moments earlier.
        List<RefreshToken> family = refreshTokenRepository.findAllByFamilyId(familyId);
        assertThat(family).hasSize(2);
        assertThat(family).allSatisfy(token -> assertThat(token.isRevoked()).isTrue());
    }

    @Test
    void concurrentRegistrationOfTheSameEmailYieldsOneUser() throws Exception {
        int threads = 8;
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    release.await();
                    try {
                        authService.register("contended@payvero.dev", "correct horse battery");
                        return Outcome.WON;
                    } catch (EmailAlreadyExistsException e) {
                        return Outcome.REJECTED_AS_DUPLICATE;
                    } catch (DataIntegrityViolationException e) {
                        return Outcome.LEAKED_PERSISTENCE_EXCEPTION;
                    }
                }));
            }
            release.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            // A losing thread that slipped past existsByEmail reaches saveAndFlush
            // and must come back as a translated domain error. With save() instead
            // the insert would defer to commit, outside the catch, and surface
            // here as a leaked persistence exception.
            assertThat(outcomes).filteredOn(Outcome.WON::equals).hasSize(1);
            assertThat(outcomes).doesNotContain(Outcome.LEAKED_PERSISTENCE_EXCEPTION);
        } finally {
            pool.shutdownNow();
        }

        assertThat(userRepository.findByEmail("contended@payvero.dev")).isPresent();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void rotationIssuesADifferentTokenInTheSameFamily() {
        TokenPair initial = authService.register("rotator@payvero.dev", "correct horse battery");
        UUID familyId = familyOf(initial.refreshToken());

        TokenPair rotated = authService.refresh(initial.refreshToken());

        // Rotation that handed back the same value would satisfy every other
        // assertion here while rotating nothing at all.
        assertThat(rotated.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(familyOf(rotated.refreshToken())).isEqualTo(familyId);

        RefreshToken previous = findByRaw(initial.refreshToken());
        assertThat(previous.isRevoked()).isTrue();

        RefreshToken successor = findByRaw(rotated.refreshToken());
        assertThat(successor.isRevoked()).isFalse();

        assertThat(authService.refresh(rotated.refreshToken())).isNotNull();
    }

    @Test
    void expiredRefreshTokenIsRejectedWithoutTouchingTheClockBean() {
        User user = userRepository.saveAndFlush(new User("stale@payvero.dev", "hash", Role.USER));
        String raw = "raw-token-that-has-already-lapsed";
        refreshTokenRepository.saveAndFlush(new RefreshToken(
                user,
                AuthService.hashToken(raw),
                UUID.randomUUID(),
                clock.instant().minus(1, ChronoUnit.MINUTES)));

        assertThatThrownBy(() -> authService.refresh(raw))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .isNotInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        assertThatThrownBy(() -> authService.refresh("no-such-token"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logoutRevokesTheFamilyAndIsIdempotent() {
        TokenPair initial = authService.register("leaver@payvero.dev", "correct horse battery");
        UUID familyId = familyOf(initial.refreshToken());

        authService.logout(initial.refreshToken());

        assertThat(refreshTokenRepository.findAllByFamilyId(familyId))
                .allSatisfy(token -> assertThat(token.isRevoked()).isTrue());

        authService.logout(initial.refreshToken());
        authService.logout("a-token-that-was-never-issued");

        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void loginStartsANewFamilySoOtherSessionsSurvive() {
        authService.register("multi@payvero.dev", "correct horse battery");
        TokenPair second = authService.login("multi@payvero.dev", "correct horse battery");
        TokenPair third = authService.login("MULTI@payvero.dev", "correct horse battery");

        assertThat(familyOf(second.refreshToken())).isNotEqualTo(familyOf(third.refreshToken()));

        authService.logout(second.refreshToken());

        // Revoking one lineage must leave the other usable.
        assertThat(authService.refresh(third.refreshToken())).isNotNull();
    }

    private UUID familyOf(String rawToken) {
        return findByRaw(rawToken).getFamilyId();
    }

    private RefreshToken findByRaw(String rawToken) {
        return refreshTokenRepository.findByTokenHash(AuthService.hashToken(rawToken))
                .orElseThrow(() -> new AssertionError("no stored token for the supplied raw value"));
    }

    private enum Outcome {
        WON,
        REJECTED_AS_REUSE,
        REJECTED_AS_DUPLICATE,
        LEAKED_PERSISTENCE_EXCEPTION
    }
}
