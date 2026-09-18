package br.com.juntaai.service;

import br.com.juntaai.dto.dashboard.DashboardResponse;
import br.com.juntaai.entity.TransactionType;
import br.com.juntaai.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Camada de consulta/DTO sobre os dados existentes — sem entidade própria,
 * como definido no documento de estrutura de repositórios (seção 7).
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TransactionRepository transactionRepository;

    public DashboardResponse summarize(UUID userId, LocalDate from, LocalDate to) {
        BigDecimal totalIncome = transactionRepository.sumByUserAndTypeAndPeriod(
                userId, TransactionType.INCOME, from, to);
        BigDecimal totalExpense = transactionRepository.sumByUserAndTypeAndPeriod(
                userId, TransactionType.EXPENSE, from, to);

        BigDecimal balance = totalIncome.subtract(totalExpense);

        // Breakdown por categoria fica como próximo incremento (P1); aqui devolvemos
        // a estrutura já pronta para o frontend consumir quando a query for adicionada.
        List<DashboardResponse.CategoryBreakdown> breakdown = List.of();

        return new DashboardResponse(from, to, totalIncome, totalExpense, balance, breakdown);
    }
}
