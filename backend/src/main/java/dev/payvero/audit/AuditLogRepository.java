package dev.payvero.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    boolean existsByEventId(UUID eventId);

    List<AuditLogEntry> findAllByAggregateId(UUID aggregateId);

    Page<AuditLogEntry> findAllByOrderByRecordedAtDesc(Pageable pageable);

    Page<AuditLogEntry> findAllByAggregateTypeOrderByRecordedAtDesc(String aggregateType, Pageable pageable);
}
