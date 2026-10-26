package br.com.juntaai.controller;

import br.com.juntaai.dto.goal.GoalProgressRequest;
import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.dto.goal.GoalResponse;
import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.GoalService;
import br.com.juntaai.service.GoalCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import br.com.juntaai.exception.BusinessRuleException;
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
    private final GoalCommandService commands;

    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<java.util.Map<String, String>> invalidIdentifier(Exception ignored) {
        return ResponseEntity.badRequest().body(java.util.Map.of("message", "Identificador inválido ou ausente. Use UUID no Idempotency-Key das operações de escrita."));
    }

    @Operation(summary = "Criar meta")
    @PostMapping
    public ResponseEntity<GoalResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                @RequestHeader HttpHeaders headers,
                                                @Valid @RequestBody GoalRequest body) {
        return ResponseEntity.ok(commands.create(user.getId(), idempotencyKey(headers), body));
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
                                                      @RequestHeader HttpHeaders headers,
                                                      @PathVariable UUID id,
                                                      @Valid @RequestBody GoalProgressRequest body) {
        return ResponseEntity.ok(commands.progress(user.getId(), idempotencyKey(headers), id, body.amount()));
    }

    @Operation(summary = "Remover meta")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                       @RequestHeader HttpHeaders headers) {
        commands.delete(user.getId(), idempotencyKey(headers), id);
        return ResponseEntity.noContent().build();
    }

    private UUID idempotencyKey(HttpHeaders headers) {
        List<String> values = headers.get("Idempotency-Key");
        if (values != null && values.size() == 1) {
            try {
                String value = values.getFirst();
                UUID key = UUID.fromString(value);
                if (key.toString().equalsIgnoreCase(value)) return key;
            } catch (IllegalArgumentException ignored) { /* Invalid input is never an identity. */ }
        }
        throw new BusinessRuleException("Use um único Idempotency-Key com UUID válido.");
    }
}
