package dev.payvero.transfer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    @Query("""
            select t from Transfer t
            where t.sourceAccount.id in :accountIds or t.destinationAccount.id in :accountIds
            order by t.createdAt desc
            """)
    Page<Transfer> findForAccounts(@Param("accountIds") List<UUID> accountIds, Pageable pageable);

    /**
     * Both parties resolved in one query, so a page of transfers can be rendered
     * with counterparty names without an N+1 walk through lazy associations.
     * The joins to users are outer because the treasury has no owner.
     */
    @Query("""
            select new dev.payvero.transfer.TransferListItem(
                t.id, sa.id, da.id, su.email, du.email, sa.accountType, da.accountType,
                t.amount, t.currency, t.status, t.failureReason, t.createdAt, t.settledAt)
            from Transfer t
            join t.sourceAccount sa
            join t.destinationAccount da
            left join sa.user su
            left join da.user du
            where sa.id in :accountIds or da.id in :accountIds
            order by t.createdAt desc
            """)
    Page<TransferListItem> findListItemsForAccounts(@Param("accountIds") List<UUID> accountIds, Pageable pageable);

    @Query("""
            select new dev.payvero.transfer.TransferListItem(
                t.id, sa.id, da.id, su.email, du.email, sa.accountType, da.accountType,
                t.amount, t.currency, t.status, t.failureReason, t.createdAt, t.settledAt)
            from Transfer t
            join t.sourceAccount sa
            join t.destinationAccount da
            left join sa.user su
            left join da.user du
            where t.id = :transferId
            """)
    Optional<TransferListItem> findListItem(@Param("transferId") UUID transferId);

    /**
     * Settlement candidates, oldest first and capped, so a backlog is worked
     * through in fair order rather than a single poll trying to load everything.
     */
    @Query("""
            select t from Transfer t
            where t.status = dev.payvero.transfer.TransferStatus.PENDING
              and t.createdAt <= :settleBefore
            order by t.createdAt asc
            """)
    List<Transfer> findSettlementCandidates(@Param("settleBefore") Instant settleBefore, Pageable pageable);

    long countByStatus(TransferStatus status);

    /**
     * One query, no lazy navigation, so a consumer outside a session can still
     * learn who a transfer belongs to. The left join keeps treasury funded
     * movements visible with a null owner rather than dropping them.
     */
    @Query("""
            select new dev.payvero.transfer.TransferVelocityFacts(t.id, t.amount, a.accountType, u.id)
            from Transfer t
            join t.sourceAccount a
            left join a.user u
            where t.id = :transferId
            """)
    Optional<TransferVelocityFacts> findVelocityFacts(@Param("transferId") UUID transferId);
}
