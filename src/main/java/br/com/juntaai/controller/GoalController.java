package br.com.juntaai.controller;

import br.com.juntaai.dto.goal.GoalProgressRequest;
import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.dto.goal.GoalResponse;
import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.GoalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/goals")
@RequiredArgsConstructor
@Tag(name = "Metas", description = "CRUD de metas financeiras do usuário autenticado")
public class GoalController {

    private final GoalService goalService;

    @Operation(summary = "Criar meta")
    @PostMapping
    public ResponseEntity<GoalResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                @Valid @RequestBody GoalRequest body) {
        return ResponseEntity.ok(goalService.create(user.getId(), body));
    }

    @Operation(summary = "Listar metas do usuário")
    @GetMapping
    public ResponseEntity<List<GoalResponse>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(goalService.listForUser(user.getId()));
    }

    @Operation(summary = "Atualizar meta")
    @PutMapping("/{id}")
    public ResponseEntity<GoalResponse> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                @PathVariable UUID id,
                                                @Valid @RequestBody GoalRequest body) {
        return ResponseEntity.ok(goalService.update(user.getId(), id, body));
    }

    @Operation(summary = "Registrar aporte / progresso na meta")
    @PatchMapping("/{id}/progress")
    public ResponseEntity<GoalResponse> addProgress(@AuthenticationPrincipal AuthenticatedUser user,
                                                      @PathVariable UUID id,
                                                      @Valid @RequestBody GoalProgressRequest body) {
        return ResponseEntity.ok(goalService.addProgress(user.getId(), id, body.amount()));
    }

    @Operation(summary = "Remover meta")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        goalService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
