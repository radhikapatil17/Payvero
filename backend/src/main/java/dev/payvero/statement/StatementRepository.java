package dev.payvero.statement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository extends JpaRepository<Statement, UUID> {

    Optional<Statement> findByAccountIdAndPeriod(UUID accountId, String period);

    List<Statement> findAllByAccountIdOrderByPeriodDesc(UUID accountId);

    boolean existsByAccountIdAndPeriod(UUID accountId, String period);
}
