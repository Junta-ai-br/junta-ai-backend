package br.com.juntaai.service;

import br.com.juntaai.repository.GoalRepository;
import br.com.juntaai.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Current organizational reserves, independent of the dashboard's selected period. */
@Service
@RequiredArgsConstructor
public class FinancialPositionService {
    private final TransactionRepository transactions;
    private final GoalRepository goals;

    public Position current(UUID user) {
        BigDecimal total = transactions.accumulatedBalance(user, LocalDate.now(ZoneId.of("America/Sao_Paulo")));
        BigDecimal saved = goals.sumCurrentAmountByUserId(user);
        if (total == null) total = BigDecimal.ZERO;
        if (saved == null) saved = BigDecimal.ZERO;
        return new Position(total.subtract(saved), total, saved.compareTo(total.max(BigDecimal.ZERO)) > 0);
    }

    public record Position(BigDecimal availableBalance, BigDecimal totalTracked, boolean reservationInconsistent) {}
}
