package br.com.juntaai.repository;

import br.com.juntaai.entity.EmailAccessCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailAccessCodeRepository extends JpaRepository<EmailAccessCode, UUID> {

    /** Só o código mais recente ainda não usado vale; os anteriores são invalidados ao pedir um novo. */
    Optional<EmailAccessCode> findFirstByEmailAndUsedFalseOrderByCreatedAtDesc(String email);

    Optional<EmailAccessCode> findFirstByEmailOrderByCreatedAtDesc(String email);

    long countByEmailAndCreatedAtAfter(String email, Instant since);

    /** Invalida os códigos ativos do e-mail (chamado antes de gerar um novo). */
    @Modifying
    @Query("update EmailAccessCode c set c.used = true where c.email = :email and c.used = false")
    int invalidateActiveCodes(@Param("email") String email);

    /**
     * Reserva UMA tentativa de forma atômica no banco. Devolve 0 se o limite
     * já foi atingido ou o código já foi usado. Ser atômico impede que
     * 100 requisições paralelas contornem o limite de tentativas.
     */
    @Modifying
    @Query("update EmailAccessCode c set c.attempts = c.attempts + 1 "
            + "where c.id = :id and c.used = false and c.attempts < :maxAttempts")
    int claimAttempt(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts);

    /** Consome o código de forma atômica: só uma requisição consegue usá-lo. */
    @Modifying
    @Query("update EmailAccessCode c set c.used = true where c.id = :id and c.used = false")
    int markUsed(@Param("id") UUID id);

    @Modifying
    @Query("delete from EmailAccessCode c where c.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}