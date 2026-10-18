package br.com.juntaai.controller;

import br.com.juntaai.dto.planner.SimulationRequest;
import br.com.juntaai.dto.planner.SimulationResponse;
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
