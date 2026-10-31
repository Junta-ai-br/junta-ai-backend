package br.com.juntaai.repository;

import br.com.juntaai.entity.Goal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    @org.springframework.data.jpa.repository.Query("select coalesce(sum(g.currentAmount), 0) from Goal g where g.user.id = :userId")
    java.math.BigDecimal sumCurrentAmountByUserId(UUID userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from Goal g where g.id = :id and g.user.id = :userId")
    Optional<Goal> findLockedByIdAndUserId(UUID id, UUID userId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from Goal g where g.user.id = :userId and lower(trim(g.name)) = lower(trim(:name)) order by g.id")
    List<Goal> findLockedByUserIdAndName(UUID userId, String name);
    List<Goal> findByUserIdOrderByDeadlineAsc(UUID userId);
    Optional<Goal> findByIdAndUserId(UUID id, UUID userId);
}
