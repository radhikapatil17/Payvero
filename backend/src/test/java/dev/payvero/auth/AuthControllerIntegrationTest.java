package dev.payvero.auth;

import dev.payvero.TestcontainersConfiguration;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real filter chain, so authentication and authorization outcomes
 * are observed as a client would see them rather than as the service reports
 * them. Not transactional, for the same reason as the service integration test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private Clock clock;

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void registerReturnsCreatedWithABearerPair() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("newcomer@payvero.dev", "correct horse battery")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300));
    }

    @Test
    void registeringATakenEmailReturnsConflict() throws Exception {
        register("taken@payvero.dev");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("taken@payvero.dev", "correct horse battery")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void invalidPayloadReturnsFieldLevelValidationErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.email").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.password").isNotEmpty());
    }

    @Test
    void failedLoginRevealsNothingAboutWhichEmailsExist() throws Exception {
        register("known@payvero.dev");

        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("known@payvero.dev", "wrong password entirely")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("nobody@payvero.dev", "wrong password entirely")))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode a = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode b = objectMapper.readTree(unknownEmail.getResponse().getContentAsString());

        assertThat(a.get("error").asString()).isEqualTo(b.get("error").asString());
        assertThat(a.get("message").asString()).isEqualTo(b.get("message").asString());
    }

    /**
     * Spring forwards an unhandled exception to /error, and while that path was
     * not permitted the forward was itself unauthenticated, so a malformed body
     * came back as 401. Failing closed hid the real cause from every client.
     */
    @Test
    void aMalformedBodyOnAPublicRouteIsABadRequestNotAnAuthFailure() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST_BODY"))
                .andReturn();

        // The parser's own message can echo the payload and internals, so the
        // response carries a fixed string instead.
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("select ")
                .doesNotContain("dev.payvero")
                .doesNotContain("org.springframework");
    }

    @Test
    void aNonUuidPathValueIsABadRequestNotAnAuthFailure() throws Exception {
        String accessToken = register("typed@payvero.dev").get("accessToken").asString();

        MvcResult result = mockMvc.perform(get("/api/accounts/not-a-uuid/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("dev.payvero")
                .doesNotContainIgnoringCase("select ");
    }

    @Test
    void aProtectedRouteWithoutATokenIsUnauthorised() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void garbageBearerTokenIsUnauthorisedRatherThanAServerError() throws Exception {
        mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer this-is-not-a-jwt-at-all"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void wellSignedTokenCarryingAnUnknownRoleIsUnauthorisedRatherThanAServerError() throws Exception {
        // Signed with the real key, so it passes verification and fails only at
        // Role.valueOf. Without the catch in the filter this is a 500.
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "impostor@payvero.dev")
                .claim("role", "SUPREME_OVERLORD")
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(5, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();

        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Points at a route that really exists, so a pass means the ADMIN rule
     * rejected the request rather than the path simply being unmapped.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/ledger/integrity",
            "/api/admin/fraud-flags",
            "/api/admin/audit-log",
            "/actuator/metrics"
    })
    void aUserTokenIsForbiddenFromAdminRoutes(String path) throws Exception {
        String accessToken = register("plain-" + UUID.randomUUID() + "@payvero.dev")
                .get("accessToken").asString();

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    /**
     * The counterpart the rejection cases need: every one of those paths is a
     * route that really exists, so a 403 means the role rule refused the
     * request rather than the path simply being unmapped. Without this an
     * admin endpoint could be deleted entirely and the tests above would still
     * pass, reporting security on a route that no longer serves anything.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/ledger/integrity",
            "/api/admin/fraud-flags",
            "/api/admin/audit-log",
            "/actuator/metrics"
    })
    void anAdminTokenReachesEveryOneOfThoseRoutes(String path) throws Exception {
        String email = "boss-" + UUID.randomUUID() + "@payvero.dev";
        register(email);

        // No endpoint grants a role, deliberately, so the promotion happens
        // through the domain rather than through an escalation path in the API.
        User admin = userRepository.findByEmail(email).orElseThrow();
        admin.assignRole(Role.ADMIN);
        userRepository.saveAndFlush(admin);

        String adminToken = login(email).get("accessToken").asString();

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    /**
     * The counterpart every rejection test needs: proof the filter actually
     * authenticates rather than merely refusing everything. Without this, a
     * filter that rejected all tokens would still pass every other case here.
     */
    @Test
    void aFreshlyIssuedTokenReachesAProtectedRoute() throws Exception {
        String accessToken = register("welcome@payvero.dev").get("accessToken").asString();

        mockMvc.perform(get("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void refreshRotatesAndAReplayedTokenIsRejected() throws Exception {
        String refreshToken = register("rotate@payvero.dev").get("refreshToken").asString();

        MvcResult rotated = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String successor = objectMapper.readTree(rotated.getResponse().getContentAsString())
                .get("refreshToken").asString();
        assertThat(successor).isNotEqualTo(refreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logoutIsNoContentEvenForATokenThatWasNeverIssued() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"never-issued-anywhere\"}"))
                .andExpect(status().isNoContent());
    }

    private JsonNode register(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "correct horse battery")))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Re-issues a token after a role change, since the old one still carries the old role. */
    private JsonNode login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "correct horse battery")))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
