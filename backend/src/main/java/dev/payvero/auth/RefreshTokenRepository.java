package dev.payvero.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByFamilyId(UUID familyId);

    /**
     * Compare-and-swap: revokes the token only if it is still active, and
     * reports how many rows that touched. Under concurrent rotation exactly one
     * caller sees 1 and the rest see 0, which is what makes the winner unique
     * without taking a lock.
     * <p>
     * The persistence context is intentionally not cleared: callers still need
     * the entity's associations after this runs, and no caller re-reads the
     * revoked column in the same transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
    int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Revokes every still-active token in a lineage in one statement, so the
     * kill cannot be interleaved with a rotation that is already in flight.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);
}
