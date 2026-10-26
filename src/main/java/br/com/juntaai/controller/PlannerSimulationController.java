package br.com.juntaai.controller;

import br.com.juntaai.dto.planner.SimulationRequest;
import br.com.juntaai.dto.planner.SimulationResponse;
import br.com.juntaai.dto.planner.GoalImportRequest;
import br.com.juntaai.dto.goal.GoalResponse;
import br.com.juntaai.service.GoalCommandService;
import br.com.juntaai.exception.BusinessRuleException;
import org.springframework.http.HttpHeaders;
import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.PlannerSimulationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/planner/simulations")
@RequiredArgsConstructor
public class PlannerSimulationController {
    private final PlannerSimulationService service;
    private final GoalCommandService commands;

    @PostMapping("/{id}/goal")
    public ResponseEntity<GoalResponse> importGoal(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @RequestHeader HttpHeaders headers, @Valid @RequestBody GoalImportRequest body) {
        var keys = headers.get("Idempotency-Key");
        if (keys != null && keys.size() == 1) {
            try {
                UUID key = UUID.fromString(keys.getFirst());
                if (key.toString().equalsIgnoreCase(keys.getFirst())) {
                    return ResponseEntity.ok(commands.importSimulation(user.getId(), key, id, body));
                }
            } catch (IllegalArgumentException ignored) { /* Reject ambiguous/invalid identity. */ }
        }
        throw new BusinessRuleException("Use um único Idempotency-Key com UUID válido.");
    }

    @PostMapping
    public ResponseEntity<SimulationResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SimulationRequest body) {
        return ResponseEntity.ok(service.create(user.getId(), body));
    }

    @GetMapping
    public ResponseEntity<List<SimulationResponse>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(service.listForUser(user.getId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        service.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
