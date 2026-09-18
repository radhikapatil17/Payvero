package dev.payvero.fraud;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, UUID> {

    boolean existsByTransferIdAndRule(UUID transferId, FraudRule rule);

    List<FraudFlag> findAllByTransferId(UUID transferId);

    Page<FraudFlag> findAllByStatusOrderByCreatedAtDesc(FraudFlagStatus status, Pageable pageable);

    Page<FraudFlag> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(FraudFlagStatus status);
}
