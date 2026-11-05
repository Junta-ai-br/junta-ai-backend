package br.com.juntaai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Exclusão definitiva da conta e de todos os dados do usuário (LGPD).
 * Tudo roda em uma única transação: ou apaga tudo, ou não apaga nada.
 */
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final JdbcTemplate jdbc;

    @Transactional
    public void deleteAccount(UUID userId) {
        List<String> emails = jdbc.queryForList(
                "SELECT email FROM users WHERE id = ?", String.class, userId);
        if (emails.isEmpty()) {
            return; // já excluída: operação idempotente
        }

        // Tabelas sem ON DELETE CASCADE (ou com RESTRICT) precisam sair primeiro, nesta ordem.
        jdbc.update("DELETE FROM conversation_message_entries WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM action_executions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM transactions WHERE user_id = ?", userId);

        // Códigos de acesso não têm user_id: ligam-se pelo e-mail.
        jdbc.update("DELETE FROM email_access_codes WHERE email = ?", emails.get(0));

        // O restante cai em cascata: categories, goals, conversations, messages,
        // refresh_tokens, onboarding_answers, planner_simulations,
        // goal_command_receipts e planner_goal_imports.
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }
}