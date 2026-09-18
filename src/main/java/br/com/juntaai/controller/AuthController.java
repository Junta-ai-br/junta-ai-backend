package br.com.juntaai.controller;

import br.com.juntaai.dto.auth.AccessCodeRequest;
import br.com.juntaai.dto.auth.AccessCodeVerifyRequest;
import br.com.juntaai.dto.auth.GoogleLoginRequest;
import br.com.juntaai.dto.auth.RefreshRequest;
import br.com.juntaai.dto.auth.RegisterRequest;
import br.com.juntaai.dto.auth.TokenResponse;
import br.com.juntaai.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Sem login por senha. Duas formas de entrar: Google ou código de 6
 * dígitos enviado por e-mail — espelha src/components/Login/LoginForm.jsx
 * e src/components/Cadastro/Cadastro.jsx do frontend.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticação", description = "Cadastro, login com Google, login por código de e-mail, refresh e logout")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Cadastrar novo usuário (nome, e-mail, WhatsApp + onboarding opcional)")
    @PostMapping("/register")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest body) {
        return ResponseEntity.ok(authService.register(body));
    }

    @Operation(summary = "Solicitar código de acesso de 6 dígitos por e-mail")
    @PostMapping("/access-code/request")
    public ResponseEntity<Void> requestAccessCode(@Valid @RequestBody AccessCodeRequest body) {
        authService.requestAccessCode(body.email());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Confirmar o código de acesso e obter os tokens")
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
