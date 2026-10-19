package br.com.juntaai.repository;

import br.com.juntaai.entity.PlannerSimulation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlannerSimulationRepository extends JpaRepository<PlannerSimulation, UUID> {
    List<PlannerSimulation> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId);
    Optional<PlannerSimulation> findByIdAndUserId(UUID id, UUID userId);
}
