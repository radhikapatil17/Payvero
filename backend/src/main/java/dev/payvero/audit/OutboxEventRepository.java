package dev.payvero.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Oldest first, so events reach Kafka in the order they were committed.
     * Bounded, so a backlog is drained in batches rather than in one transaction
     * that holds locks across the whole table.
     */
    @Query("""
            select e from OutboxEvent e
            where e.publishedAt is null
            order by e.createdAt asc
            """)
    List<OutboxEvent> findPending(Pageable pageable);

    long countByPublishedAtIsNull();

    List<OutboxEvent> findAllByAggregateId(UUID aggregateId);
}
