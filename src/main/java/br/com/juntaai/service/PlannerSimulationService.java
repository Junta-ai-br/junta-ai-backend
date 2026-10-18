package br.com.juntaai.service;

import br.com.juntaai.dto.planner.SimulationRequest;
import br.com.juntaai.dto.planner.SimulationResponse;
import br.com.juntaai.entity.PlannerSimulation;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.repository.PlannerSimulationRepository;
import br.com.juntaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlannerSimulationService {
    private final PlannerSimulationRepository repository;
    private final UserRepository users;

    @Transactional
    public SimulationResponse create(UUID userId, SimulationRequest request) {
        var simulation = new PlannerSimulation();
        simulation.setUser(users.getReferenceById(userId));
        simulation.setGoalName(request.goalName().trim());
        simulation.setGoalAmount(request.goalAmount());
        simulation.setDeadlineMonths(request.deadlineMonths());
        simulation.setAnalysisBase(request.analysisBase());
        simulation.setMonthlyAmount(request.monthlyAmount());
        simulation.setEstimatedMonths(request.estimatedMonths());
        simulation.setHistoryContext(request.historyContext());
        return toResponse(repository.save(simulation));
    }

    @Transactional(readOnly = true)
    public List<SimulationResponse> listForUser(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        var simulation = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Simulação"));
        repository.delete(simulation);
    }

    private SimulationResponse toResponse(PlannerSimulation s) {
        return new SimulationResponse(s.getId(), s.getGoalName(), s.getGoalAmount(),
                s.getDeadlineMonths(), s.getAnalysisBase(), s.getMonthlyAmount(),
                s.getEstimatedMonths(), s.getHistoryContext(), s.getCreatedAt());
    }
}
