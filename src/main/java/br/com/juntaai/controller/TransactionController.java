package br.com.juntaai.controller;

import br.com.juntaai.dto.transaction.TransactionRequest;
import br.com.juntaai.dto.transaction.TransactionResponse;
import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
@Tag(name = "Transações", description = "CRUD de receitas e despesas do usuário autenticado")
public class TransactionController {

    private final TransactionService transactionService;

    @Operation(summary = "Criar transação (receita ou despesa)")
    @PostMapping
    public ResponseEntity<TransactionResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @Valid @RequestBody TransactionRequest body) {
        return ResponseEntity.ok(transactionService.create(user.getId(), body));
    }

    @Operation(summary = "Listar transações do usuário, paginado e filtrado por período")
    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Pageable pageable) {

        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(1);

        return ResponseEntity.ok(transactionService.listForUser(user.getId(), effectiveFrom, effectiveTo, pageable));
    }

    @Operation(summary = "Atualizar transação")
    @PutMapping("/{id}")
    public ResponseEntity<TransactionResponse> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody TransactionRequest body) {
        return ResponseEntity.ok(transactionService.update(user.getId(), id, body));
    }

    @Operation(summary = "Remover transação")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        transactionService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
