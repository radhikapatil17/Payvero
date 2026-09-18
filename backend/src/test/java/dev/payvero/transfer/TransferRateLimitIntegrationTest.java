package dev.payvero.transfer;

import dev.payvero.TestcontainersConfiguration;
import dev.payvero.auth.Role;
import dev.payvero.auth.User;
import dev.payvero.auth.UserRepository;
import dev.payvero.accounting.Account;
import dev.payvero.accounting.AccountService;
import dev.payvero.accounting.AccountingService;
import dev.payvero.transfer.dto.CreateTransferRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A tiny window so the limit is reachable without firing sixty requests. The
 * limiter itself is unchanged; only the configured allowance differs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "payvero.settlement.enabled=false",
        "payvero.transfer.rate-limit-per-window=3",
        "payvero.transfer.rate-limit-window=PT1M"
})
class TransferRateLimitIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountingService ledgerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceUserId;
    private UUID aliceId;
    private UUID bobId;

    @BeforeEach
    void setUp() {
        User alice = userRepository.saveAndFlush(new User("alice@payvero.dev", "hash", Role.USER));
        User bob = userRepository.saveAndFlush(new User("bob@payvero.dev", "hash", Role.USER));
        aliceUserId = alice.getId();
        aliceId = accountService.openAccount(aliceUserId, "USD").getId();
        bobId = accountService.openAccount(bob.getId(), "USD").getId();
    }

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
    void theAllowanceIsSpentAndThenTransfersAreRefused() {
        // The deposit consumes one slot of the three, because funding is a
        // transfer like any other and is rate limited the same way.
        transferService.deposit(aliceUserId, aliceId, 100_000, null);

        transferService.createTransfer(aliceUserId, new CreateTransferRequest(aliceId, bobId, 100), null);
        transferService.createTransfer(aliceUserId, new CreateTransferRequest(aliceId, bobId, 100), null);

        assertThatThrownBy(() -> transferService.createTransfer(
                aliceUserId, new CreateTransferRequest(aliceId, bobId, 100), null))
                .isInstanceOf(RateLimitExceededException.class);

        // A refused request is refused before any money moves.
        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(200);
        assertThat(ledgerService.checkIntegrity().healthy()).isTrue();
    }

    /**
     * A replay is not new work, so it must not spend the allowance. Otherwise a
     * client retrying after a timeout would be punished for the network's fault.
     */
    @Test
    void replayingAnAnsweredKeyDoesNotSpendTheAllowance() {
        transferService.deposit(aliceUserId, aliceId, 100_000, null);
        CreateTransferRequest request = new CreateTransferRequest(aliceId, bobId, 100);

        transferService.createTransfer(aliceUserId, request, "replayable");

        // Two slots used so far. Replaying many times must not consume more.
        for (int i = 0; i < 5; i++) {
            transferService.createTransfer(aliceUserId, request, "replayable");
        }

        // The third slot is therefore still available for genuinely new work.
        transferService.createTransfer(aliceUserId, new CreateTransferRequest(aliceId, bobId, 100), null);

        assertThat(ledgerService.derivedBalance(bobId)).isEqualTo(200);
    }
}
