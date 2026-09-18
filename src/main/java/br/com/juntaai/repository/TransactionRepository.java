package br.com.juntaai.repository;

import br.com.juntaai.entity.Transaction;
import br.com.juntaai.entity.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);

    Page<Transaction> findByUserIdAndTransactionDateBetween(
            UUID userId, LocalDate from, LocalDate to, Pageable pageable);

    @Query("""
           select coalesce(sum(t.amount), 0) from Transaction t
           where t.user.id = :userId and t.type = :type
           and t.transactionDate between :from and :to
           """)
    BigDecimal sumByUserAndTypeAndPeriod(@Param("userId") UUID userId,
                                          @Param("type") TransactionType type,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);
}
