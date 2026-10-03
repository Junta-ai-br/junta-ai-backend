package br.com.juntaai.controller;

import br.com.juntaai.dto.auth.AccessCodeRequest;
import br.com.juntaai.dto.auth.AccessCodeVerifyRequest;
import br.com.juntaai.dto.auth.GoogleLoginRequest;
import br.com.juntaai.dto.auth.RefreshRequest;
import br.com.juntaai.dto.auth.RegisterCodeRequest;
import br.com.juntaai.dto.auth.RegisterVerifyRequest;
import br.com.juntaai.dto.auth.TokenResponse;
import br.com.juntaai.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticação", description = "Cadastro em duas etapas (e-mail validado antes de criar a conta), login com Google, login por código de e-mail, refresh e logout")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Cadastro — passo 1: envia o código de confirmação para o e-mail")
    @PostMapping("/register/request-code")
    public ResponseEntity<Void> requestRegistrationCode(@Valid @RequestBody RegisterCodeRequest body) {
        authService.requestRegistrationCode(body);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Cadastro — passo 2: confirma o código e cria a conta (nome, WhatsApp, onboarding)")
    @PostMapping("/register/verify")
    public ResponseEntity<TokenResponse> completeRegistration(@Valid @RequestBody RegisterVerifyRequest body) {
        return ResponseEntity.ok(authService.completeRegistration(body));
    }

    @Operation(summary = "Solicitar código de acesso de 6 dígitos por e-mail (login)")
    @PostMapping("/access-code/request")
    public ResponseEntity<Void> requestAccessCode(@Valid @RequestBody AccessCodeRequest body) {
        authService.requestAccessCode(body.email());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Confirmar o código de acesso e obter os tokens (login)")
    @PostMapping("/access-code/verify")
    public ResponseEntity<TokenResponse> verifyAccessCode(@Valid @RequestBody AccessCodeVerifyRequest body) {
        return ResponseEntity.ok(authService.verifyAccessCode(body));
    }

    @Operation(summary = "Login com Google (troca o ID Token pelos tokens da aplicação)")
    @PostMapping("/google")
    public ResponseEntity<TokenResponse> loginWithGoogle(@Valid @RequestBody GoogleLoginRequest body) {
        return ResponseEntity.ok(authService.loginWithGoogle(body.idToken()));
    }

    @Operation(summary = "Renovar tokens usando refresh token")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest body) {
        return ResponseEntity.ok(authService.refresh(body.refreshToken()));
    }

    @Operation(summary = "Fazer logout (revoga o refresh token)")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest body) {
        authService.logout(body.refreshToken());
        return ResponseEntity.noContent().build();
    }
}