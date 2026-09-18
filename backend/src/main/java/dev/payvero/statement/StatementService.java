package dev.payvero.statement;

import dev.payvero.accounting.JournalEntry;
import dev.payvero.accounting.JournalEntryRepository;
import dev.payvero.statement.dto.StatementResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Statements are derived from {@code ledger_entries} and from nothing else.
 * <p>
 * Nothing here reads {@code cached_balance}. That is the entire point: the cache
 * is an optimisation maintained by application code, so a bug in that code would
 * produce a statement that agrees perfectly with the cache while both disagree
 * with the entries. A statement's value is that it can be proved from primary
 * records, which it cannot be if it was computed from a derived one.
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final StatementWriter writer;
    private final JournalEntryRepository ledgerEntries;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StatementService(StatementRepository statements,
                            StatementWriter writer,
                            JournalEntryRepository ledgerEntries,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.statements = statements;
        this.writer = writer;
        this.ledgerEntries = ledgerEntries;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Generates the statement for a closed period, or returns the one already
     * generated. Regeneration is a read: the stored figures are returned
     * unchanged rather than recomputed, so a statement can never quietly say
     * something different the second time it is asked for.
     */
    public StatementResponse generate(UUID accountId, YearMonth period) {
        String label = label(period);

        return statements.findByAccountIdAndPeriod(accountId, label)
                .map(this::describe)
                .orElseGet(() -> generateFirstTime(accountId, period, label));
    }

    private StatementResponse generateFirstTime(UUID accountId, YearMonth period, String label) {
        requirePeriodHasEnded(period);

        Instant from = startOf(period);
        Instant to = startOf(period.plusMonths(1));

        // Both figures come from the same query against entries, evaluated at
        // the two boundary instants. Closing of one period and opening of the
        // next are therefore literally the same expression.
        long opening = ledgerEntries.deriveBalanceBefore(accountId, from);
        long closing = ledgerEntries.deriveBalanceBefore(accountId, to);

        List<JournalEntry> entries = ledgerEntries.findForPeriod(accountId, from, to);
        List<StatementLine> lines = toLines(entries, opening);

        // A cheap self-check before anything is stored: walking the lines from
        // the opening balance has to land exactly on the closing balance. If it
        // does not, the period's entries and its boundary sums disagree and the
        // statement would be internally inconsistent.
        long walked = lines.isEmpty() ? opening : lines.getLast().balanceAfterMinorUnits();
        if (walked != closing) {
            throw new StatementDerivationException(label, opening, closing, walked);
        }

        try {
            writer.insert(accountId, label, opening, closing, lines.size(), write(lines));
        } catch (DataIntegrityViolationException e) {
            // Another caller generated the same period first. The unique
            // constraint decided it; this read runs in a new transaction
            // because the losing one is aborted.
            return statements.findByAccountIdAndPeriod(accountId, label)
                    .map(this::describe)
                    .orElseThrow(() -> e);
        }

        return statements.findByAccountIdAndPeriod(accountId, label)
                .map(this::describe)
                .orElseThrow();
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID accountId, YearMonth period) {
        return statements.existsByAccountIdAndPeriod(accountId, label(period));
    }

    @Transactional(readOnly = true)
    public List<StatementResponse> listFor(UUID accountId) {
        return statements.findAllByAccountIdOrderByPeriodDesc(accountId).stream()
                .map(this::describe)
                .toList();
    }

    @Transactional(readOnly = true)
    public StatementResponse require(UUID accountId, YearMonth period) {
        return statements.findByAccountIdAndPeriod(accountId, label(period))
                .map(this::describe)
                .orElseThrow(StatementNotFoundException::new);
    }

    /** Parsed once here so the stored document never leaves as a raw string. */
    private StatementResponse describe(dev.payvero.statement.Statement statement) {
        return StatementResponse.from(statement,
                objectMapper.readValue(statement.getLineItems(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, StatementLine.class)));
    }

    private List<StatementLine> toLines(List<JournalEntry> entries, long opening) {
        List<StatementLine> lines = new ArrayList<>(entries.size());
        long running = opening;
        for (JournalEntry entry : entries) {
            running += entry.signedAmount();
            lines.add(StatementLine.of(entry, running));
        }
        return lines;
    }

    /**
     * A statement for a month still in progress would be immutable and wrong:
     * later entries in that month could never be reflected. Refusing an open
     * period is what makes immutability defensible rather than lossy.
     */
    private void requirePeriodHasEnded(YearMonth period) {
        if (!startOf(period.plusMonths(1)).isBefore(clock.instant())) {
            throw new PeriodNotClosedException(label(period));
        }
    }

    private String write(List<StatementLine> lines) {
        return objectMapper.writeValueAsString(lines);
    }

    /** UTC throughout, so a period boundary is the same instant everywhere. */
    private static Instant startOf(YearMonth period) {
        return period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static String label(YearMonth period) {
        return "%04d-%02d".formatted(period.getYear(), period.getMonthValue());
    }
}
