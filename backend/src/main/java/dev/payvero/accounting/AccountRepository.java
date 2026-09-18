package dev.payvero.accounting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByUserId(UUID userId);

    Optional<Account> findByUserIdAndCurrency(UUID userId, String currency);

    boolean existsByUserIdAndCurrency(UUID userId, String currency);
}
