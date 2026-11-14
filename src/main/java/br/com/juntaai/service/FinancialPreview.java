package br.com.juntaai.service;

import br.com.juntaai.integration.ai.model.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Backend-owned visible consent summary. Its exact persisted bytes bind the prompt to the frozen payload. */
final class FinancialPreview {
    static boolean financial(PendingOperation p) {
        return p != null && (p.operation() == Operation.EXPENSE || p.operation() == Operation.INCOME);
    }
    static String render(PendingOperation p) {
        try {
            if (!financial(p) || p.state() != PendingState.CONFIRMATION
                    || !List.of("confirmation").equals(p.unresolved_fields()) || p.target_reference()!=null) return null;
            var d=p.known_fields();
            if (d == null || d.transaction_type()!=p.operation() || d.temporal_status()!=TemporalStatus.REALIZED
                    || d.goal_reference()!=null || d.correction_target()!=null
                    || d.monetary_value()==null || d.monetary_value().signum()<=0
                    || d.monetary_value().stripTrailingZeros().scale()>2
                    || d.monetary_value().compareTo(new java.math.BigDecimal("999999999999999.99"))>0
                    || d.category()==null || d.category().isBlank() || d.category().length()>120
                    || (d.description()!=null && d.description().length()>500)) return null;
            UUID.fromString(p.pending_id()); UUID.fromString(p.conversation_id());
            LocalDate date=LocalDate.parse(d.resolved_date());
            if (!date.toString().equals(d.resolved_date()) || date.isAfter(LocalDate.now(ZoneId.of("America/Sao_Paulo")))) return null;
            String amount=d.monetary_value().setScale(2,RoundingMode.UNNECESSARY).toPlainString().replace('.',',');
            return "Confira sua " + (p.operation()==Operation.EXPENSE ? "despesa" : "receita")
                + ":\nValor: R$ " + amount + "\nCategoria: " + d.category()
                + "\nData: " + date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))
                + (d.description()==null || d.description().isEmpty() ? "" : "\nDescri\u00e7\u00e3o: " + d.description())
                + "\nPosso registrar? Use Confirmar ou Cancelar. Para corrigir, informe o dado que deseja alterar.";
        } catch (RuntimeException ex) { return null; }
    }
    static boolean validPrompt(JdbcTemplate jdbc, UUID conversation, PendingOperation p) {
        String content=render(p);
        if(content==null || !conversation.toString().equals(p.conversation_id()) || p.prompt_message_id()==null) return false;
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM messages WHERE id=? AND conversation_id=? AND role='ASSISTANT'
                    AND content=? AND created_at > clock_timestamp()-interval '10 minutes')
                """,Boolean.class,UUID.fromString(p.prompt_message_id()),conversation,content));
        } catch (IllegalArgumentException ex) { return false; }
    }
}
