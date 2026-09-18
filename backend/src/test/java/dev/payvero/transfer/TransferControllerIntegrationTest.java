package dev.payvero.transfer;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.accounting.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the shape the frontend actually consumes, which is a different question
 * from whether the service is correct.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payvero.outbox.enabled=false",
        "payvero.settlement.enabled=false",
        "payvero.transfer.rate-limit-per-window=1000"
})
class TransferControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE statements");
        jdbcTemplate.execute("TRUNCATE audit_log");
        jdbcTemplate.execute("TRUNCATE ledger_entries");
        jdbcTemplate.update("DELETE FROM fraud_flags");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM idempotency_keys");
        jdbcTemplate.update("DELETE FROM transfers");
        jdbcTemplate.update("DELETE FROM accounts WHERE account_type = 'USER'");
        jdbcTemplate.update("UPDATE accounts SET cached_balance = 0, version = 0 WHERE id = ?",
                Account.TREASURY_ID);
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM users");
    }

    /**
     * Spring warns that serializing PageImpl directly has no stable structure.
     * This asserts the DTO shape instead, so an upgrade that changed the legacy
     * form would be caught here rather than by a frontend that stopped
     * paginating.
     */
    @Test
    void theTransferListUsesTheStablePagedModelShape() throws Exception {
        String token = tokenFor("pager@payvero.dev");
        UUID account = openAccount(token);
        deposit(token, account, 50_000);

        MvcResult result = mockMvc.perform(get("/api/transfers?page=0&size=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andReturn();

        // None of PageImpl's internals leak into the contract.
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("pageable")).isFalse();
        assertThat(body.has("sort")).isFalse();
        assertThat(body.has("numberOfElements")).isFalse();
    }

    /**
     * Without these the list is a wall of uuids: a client cannot tell whether a
     * transfer took money out or brought it in without holding every account id
     * it owns, and cannot name the other party at all.
     */
    @Test
    void eachTransferIsDescribedFromTheCallersPerspective() throws Exception {
        String aliceToken = tokenFor("alice@payvero.dev");
        UUID alice = openAccount(aliceToken);
        String bobToken = tokenFor("bob@payvero.dev");
        UUID bob = openAccount(bobToken);

        deposit(aliceToken, alice, 50_000);
        mockMvc.perform(post("/api/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amountMinorUnits":12500}"""
                                .formatted(alice, bob)))
                .andExpect(status().isCreated())
                // The creating call is already described from the caller's side.
                .andExpect(jsonPath("$.direction").value("DEBIT"))
                .andExpect(jsonPath("$.counterpartyLabel").value("bob@payvero.dev"));

        // Alice sees money leaving, to Bob.
        mockMvc.perform(get("/api/transfers?size=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].counterpartyLabel").value("bob@payvero.dev"));

        // Bob sees the same transfer as money arriving, from Alice. Same row,
        // opposite description, decided server-side per caller.
        mockMvc.perform(get("/api/transfers?size=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].counterpartyLabel").value("alice@payvero.dev"));
    }

    /** Funding has no counterparty user, so it names the treasury rather than a null. */
    @Test
    void aDepositNamesTheTreasuryAsCounterparty() throws Exception {
        String token = tokenFor("funder@payvero.dev");
        UUID account = openAccount(token);
        deposit(token, account, 30_000);

        mockMvc.perform(get("/api/transfers?size=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].counterpartyLabel").value("Treasury"));
    }

    @Test
    void whoAmIResolvesTheCallerServerSide() throws Exception {
        String token = tokenFor("known@payvero.dev");

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("known@payvero.dev"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.userId").isNotEmpty());

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    private String tokenFor(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct horse battery"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
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

    private void deposit(String token, UUID accountId, long amount) throws Exception {
        mockMvc.perform(post("/api/accounts/" + accountId + "/deposits")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinorUnits\":%d}".formatted(amount)))
                .andExpect(status().isCreated());
    }
}
