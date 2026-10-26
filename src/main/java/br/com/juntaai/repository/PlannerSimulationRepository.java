package br.com.juntaai.repository;

import br.com.juntaai.entity.PlannerSimulation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlannerSimulationRepository extends JpaRepository<PlannerSimulation, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from PlannerSimulation s where s.id = :id and s.user.id = :userId")
    Optional<PlannerSimulation> findLockedByIdAndUserId(UUID id, UUID userId);
    List<PlannerSimulation> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, org.springframework.data.domain.Pageable pageable);

    long countByUserId(UUID userId);
    Optional<PlannerSimulation> findByIdAndUserId(UUID id, UUID userId);
}
