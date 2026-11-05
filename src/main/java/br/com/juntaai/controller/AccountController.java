package br.com.juntaai.controller;

import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.AccountDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me")
@RequiredArgsConstructor
@Tag(name = "Conta", description = "Gestão da própria conta")
public class AccountController {

    private final AccountDeletionService accountDeletionService;

    @Operation(summary = "Excluir definitivamente a própria conta e todos os dados")
    @DeleteMapping
    public ResponseEntity<Void> deleteMyAccount(@AuthenticationPrincipal AuthenticatedUser user) {
        accountDeletionService.deleteAccount(user.getId());
        return ResponseEntity.noContent().build();
    }
}