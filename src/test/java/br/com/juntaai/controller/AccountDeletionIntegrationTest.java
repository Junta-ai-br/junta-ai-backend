package br.com.juntaai.controller;

import br.com.juntaai.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountDeletionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private int count(String table, UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private void addTransaction(UUID userId) {
        UUID categoryId = jdbc.queryForObject(
                "SELECT id FROM categories WHERE user_id = ? LIMIT 1", UUID.class, userId);
        jdbc.update("INSERT INTO transactions (user_id, category_id, type, description, amount, transaction_date) "
                + "VALUES (?, ?, 'EXPENSE', 'teste', 10.00, CURRENT_DATE)", userId, categoryId);
    }

    @Test
    void deveApagarContaEDadosDoUsuarioSemTocarNoOutro() throws Exception {
        String emailA = uniqueEmail("apagar");
        String emailB = uniqueEmail("manter");
        String tokenA = registerAndGetAccessToken("Apagar", emailA, "11999999999");
        registerAndGetAccessToken("Manter", emailB, "11888888888");
        UUID a = userId(emailA);
        UUID b = userId(emailB);
        addTransaction(a);
        addTransaction(b);

        assertThat(count("categories", a)).isPositive();
        assertThat(count("refresh_tokens", a)).isPositive();

        mockMvc.perform(delete("/users/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, a)).isZero();
        for (String t : new String[]{"categories", "transactions", "refresh_tokens"}) {
            assertThat(count(t, a)).as(t).isZero();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM email_access_codes WHERE email = ?", Integer.class, emailA)).isZero();

        // o outro usuário continua intacto
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, b)).isOne();
        assertThat(count("categories", b)).isPositive();
        assertThat(count("transactions", b)).isOne();
    }

    @Test
    void semLoginDeveSerRecusado() throws Exception {
        mockMvc.perform(delete("/users/me")).andExpect(status().is4xxClientError());
    }
}