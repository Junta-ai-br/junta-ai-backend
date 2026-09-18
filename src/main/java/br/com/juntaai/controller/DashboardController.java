package br.com.juntaai.controller;

import br.com.juntaai.dto.dashboard.DashboardResponse;
import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "Camada de consulta agregada sobre transações e metas")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "Resumo financeiro do usuário em um período")
    @GetMapping
    public ResponseEntity<DashboardResponse> summary(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(1);

        return ResponseEntity.ok(dashboardService.summarize(user.getId(), effectiveFrom, effectiveTo));
    }
}
