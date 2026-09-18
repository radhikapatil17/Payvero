package dev.payvero.accounting;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE ledger_entries");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_type = 'USER'");
        jdbcTemplate.update("UPDATE accounts SET cached_balance = 0, version = 0 WHERE id = ?",
                Account.TREASURY_ID);
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void aUserCanOpenAnAccountDepositAndSeeAConsistentBalance() throws Exception {
        String token = tokenFor("holder@payvero.dev");
        UUID accountId = openAccount(token);

        mockMvc.perform(post("/api/accounts/" + accountId + "/deposits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinorUnits\":25000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.derivedBalanceMinorUnits").value(25000))
                .andExpect(jsonPath("$.cachedBalanceMinorUnits").value(25000))
                .andExpect(jsonPath("$.consistent").value(true));

        mockMvc.perform(get("/api/accounts/" + accountId + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedBalanceMinorUnits").value(25000))
                .andExpect(jsonPath("$.consistent").value(true));
    }

    @Test
    void openingAnAccountTwiceReturnsTheSameOne() throws Exception {
        String token = tokenFor("repeat@payvero.dev");
        UUID first = openAccount(token);
        UUID second = openAccount(token);

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);

        mockMvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * Reported as not found rather than forbidden: telling a caller the account
     * exists but is not theirs would confirm which ids are real.
     */
    @Test
    void oneUserCannotReadAnotherUsersBalance() throws Exception {
        String ownerToken = tokenFor("owner@payvero.dev");
        UUID accountId = openAccount(ownerToken);

        String intruderToken = tokenFor("intruder@payvero.dev");

        mockMvc.perform(get("/api/accounts/" + accountId + "/balance")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void oneUserCannotDepositIntoAnotherUsersAccount() throws Exception {
        String ownerToken = tokenFor("target@payvero.dev");
        UUID accountId = openAccount(ownerToken);
        String intruderToken = tokenFor("attacker@payvero.dev");

        mockMvc.perform(post("/api/accounts/" + accountId + "/deposits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinorUnits\":100000}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNonPositiveDepositIsRejectedByValidation() throws Exception {
        String token = tokenFor("picky@payvero.dev");
        UUID accountId = openAccount(token);

        mockMvc.perform(post("/api/accounts/" + accountId + "/deposits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinorUnits\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void anAdminCanRunTheIntegrityCheck() throws Exception {
        String userToken = tokenFor("customer@payvero.dev");
        UUID accountId = openAccount(userToken);
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinorUnits\":5000}"))
                .andExpect(status().isCreated());

        String adminToken = tokenFor("boss@payvero.dev", Role.ADMIN);

        mockMvc.perform(get("/api/admin/ledger/integrity")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.netOfAllEntries").value(0));
    }

    private String tokenFor(String email) throws Exception {
        return tokenFor(email, Role.USER);
    }

    /**
     * Registers through the API for USER, then promotes in the database for
     * ADMIN, because there is deliberately no endpoint that grants a role.
     */
    private String tokenFor(String email, Role role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        if (role == Role.USER) {
            return objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("accessToken").asString();
        }

        User user = userRepository.findByEmail(email).orElseThrow();
        jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role.name(), user.getId());

        MvcResult elevated = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(elevated.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    private UUID openAccount(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asString());
    }
}
