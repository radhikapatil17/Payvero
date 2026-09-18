package dev.payvero.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findAllByAccountIdOrderByCreatedAtAsc(UUID accountId);

    List<JournalEntry> findAllByTransferId(UUID transferId);

    long countByTransferId(UUID transferId);

    @Query("""
            select coalesce(sum(case when e.direction = dev.payvero.accounting.Direction.CREDIT
                                     then e.amount else -e.amount end), 0)
            from JournalEntry e
            where e.account.id = :accountId
            """)
    long deriveBalance(@Param("accountId") UUID accountId);

    @Query("""
            select coalesce(sum(case when e.direction = dev.payvero.accounting.Direction.CREDIT
                                     then e.amount else -e.amount end), 0)
            from JournalEntry e
            where e.account.id = :accountId
              and e.createdAt < :before
            """)
    long deriveBalanceBefore(@Param("accountId") UUID accountId, @Param("before") Instant before);

    @Query("""
            select e from JournalEntry e
            where e.account.id = :accountId
              and e.createdAt >= :from
              and e.createdAt < :to
            order by e.createdAt asc, e.id asc
            """)
    List<JournalEntry> findForPeriod(@Param("accountId") UUID accountId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);

    @Query(value = """
            SELECT a.id AS account_id,
                   a.cached_balance AS cached_balance,
                   COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
                       AS derived_balance
            FROM accounts a
            LEFT JOIN journal_entries e ON e.account_id = a.id
            GROUP BY a.id, a.cached_balance
            HAVING a.cached_balance <>
                   COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE -e.amount END), 0)
            """, nativeQuery = true)
    List<BalanceMismatchView> findBalanceMismatches();

    @Query(value = """
            SELECT transfer_id AS transfer_id,
                   SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END) AS debit_total,
                   SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS credit_total
            FROM journal_entries
            GROUP BY transfer_id
            HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END)
                <> SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END)
            """, nativeQuery = true)
    List<UnbalancedTransferView> findUnbalancedTransfers();

    @Query(value = """
            SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM journal_entries
            """, nativeQuery = true)
    long sumOfAllSignedEntries();
}
