package br.com.juntaai.service;

import br.com.juntaai.dto.goal.*;
import br.com.juntaai.dto.planner.GoalImportRequest;
import br.com.juntaai.exception.*;
import br.com.juntaai.repository.GoalRepository;
import br.com.juntaai.repository.PlannerSimulationRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlannerGoalImportService {
    private final PlannerSimulationRepository simulations;
    private final GoalService goals;
    private final GoalRepository goalRepository;
    private final JdbcTemplate jdbc;
    private final Validator validator;

    @Transactional
    public GoalResponse importGoal(UUID user, UUID simulationId, GoalImportRequest request) {
        var simulation = simulations.findLockedByIdAndUserId(simulationId, user)
                .orElseThrow(() -> new ResourceNotFoundException("Simulação"));
        var links = jdbc.queryForList("SELECT goal_id FROM planner_goal_imports WHERE simulation_id=? AND user_id=?", simulationId, user);
        if (!links.isEmpty()) {
            if (links.getFirst().get("goal_id") != null) throw new ConflictException("simulation_already_imported");
            if (!request.confirmReimport()) throw new ConflictException("simulation_reimport_confirmation_required");
        }
        var body = new GoalRequest(simulation.getGoalName(), simulation.getGoalAmount(), request.deadline());
        if (!validator.validate(body).isEmpty()) {
            throw new BusinessRuleException("O nome ou valor da simulação não atende aos limites de uma meta.");
        }
        var goal = goals.create(user, body); // Always zero progress; monthlyAmount is never used.
        goalRepository.flush(); // JDBC link must reference the persisted goal in this transaction.
        if (links.isEmpty()) {
            jdbc.update("INSERT INTO planner_goal_imports(simulation_id,user_id,goal_id) VALUES (?,?,?)", simulationId, user, goal.id());
        } else {
            jdbc.update("UPDATE planner_goal_imports SET goal_id=?,imported_at=now() WHERE simulation_id=? AND user_id=?", goal.id(), simulationId, user);
        }
        return goal;
    }
}
