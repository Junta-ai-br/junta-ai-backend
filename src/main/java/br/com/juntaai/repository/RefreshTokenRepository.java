package br.com.juntaai.repository;

import br.com.juntaai.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Procura pelo HASH do token (o valor em texto puro nunca é guardado). */
    Optional<RefreshToken> findByToken(String tokenHash);

    @Modifying
    @Query("delete from RefreshToken r where r.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);

    /** Revoga de forma atômica: só uma requisição consegue "gastar" o mesmo refresh token. */
    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.id = :id and r.revoked = false")
    int revokeIfActive(@Param("id") UUID id);

    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.token = :tokenHash")
    int revokeByToken(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("delete from RefreshToken r where r.expiryDate < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}