package dev.payvero.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Base64.Encoder tokenEncoder = Base64.getUrlEncoder().withoutPadding();

    /**
     * A genuine BCrypt hash of a throwaway value, compared against on the
     * unknown-email path so a failed login costs the same time whether or not
     * the address is registered. A hardcoded non-BCrypt literal would make
     * matches() bail out immediately and defeat the point.
     */
    private final String dummyHash;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties jwtProperties,
                       Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenPair register(String email, String rawPassword) {
        String normalized = normalizeEmail(email);

        if (userRepository.existsByEmail(normalized)) {
            throw new EmailAlreadyExistsException();
        }

        User user = new User(normalized, passwordEncoder.encode(rawPassword), Role.USER);
        try {
            // saveAndFlush, not save: a deferred insert would surface the unique
            // violation at commit, outside this catch, as an untranslated 500.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the race between the check above and this insert. The unique
            // constraint is the real guarantee; the check is only a nicety.
            throw new EmailAlreadyExistsException();
        }

        return issueTokens(user, UUID.randomUUID(), clock.instant());
    }

    @Transactional
    public TokenPair login(String email, String rawPassword) {
        String normalized = normalizeEmail(email);
        Optional<User> found = userRepository.findByEmail(normalized);

        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            throw new InvalidCredentialsException();
        }

        User user = found.get();
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // A new family per login, so revoking one device's lineage leaves the
        // user's other sessions alone.
        return issueTokens(user, UUID.randomUUID(), clock.instant());
    }

    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public TokenPair refresh(String rawRefreshToken) {
        Instant now = clock.instant();

        RefreshToken token = refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not recognised"));

        UUID familyId = token.getFamilyId();

        if (token.isRevoked()) {
            refreshTokenRepository.revokeFamily(familyId, now);
            throw new RefreshTokenReuseException();
        }

        if (token.getExpiresAt().isBefore(now)) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        User user = token.getUser();
        // Force the LAZY proxy to initialise while the context is known intact.
        // getId() would not do it: a Hibernate proxy can serve its own
        // identifier without touching the database.
        user.getEmail();

        int rotated = refreshTokenRepository.revokeIfActive(token.getId(), now);
        if (rotated == 0) {
            // Another request revoked this exact row between our read and our
            // write. From the server's position that is indistinguishable from
            // a replay, so it gets the same treatment.
            refreshTokenRepository.revokeFamily(familyId, now);
            throw new RefreshTokenReuseException();
        }

        return issueTokens(user, familyId, now);
    }

    /**
     * Resolves the caller from the database rather than from token claims.
     * A client can decode a JWT for display, but the moment that decoding is
     * relied on for anything it becomes an authorization decision made by the
     * party being authorized. Answering here keeps that boundary clear.
     */
    @Transactional(readOnly = true)
    public User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new InvalidCredentialsException());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        Instant now = clock.instant();
        refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), now));
    }

    private TokenPair issueTokens(User user, UUID familyId, Instant now) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = generateRefreshToken();

        refreshTokenRepository.save(new RefreshToken(
                user,
                hashToken(rawRefreshToken),
                familyId,
                now.plus(jwtProperties.refreshTokenTtl())));

        return new TokenPair(accessToken, rawRefreshToken);
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return tokenEncoder.encodeToString(bytes);
    }

    /**
     * The database's unique index is case-sensitive, so casing has to be
     * settled here and applied on every read and write path. Locale.ROOT
     * matters: default-locale lowercasing mangles a dotted I under tr_TR.
     */
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Refresh tokens are 256 bits of SecureRandom, so a fast deterministic
     * digest is both sufficient and required. BCrypt is salted, which would
     * make findByTokenHash an equality lookup that can never match; the slow
     * work factor exists to protect low-entropy human passwords, which this
     * is not. Hashing at rest still makes a database dump unreplayable.
     */
    static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
