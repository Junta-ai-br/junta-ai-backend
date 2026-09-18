package br.com.juntaai.controller;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.category.CategoryRequest;
import br.com.juntaai.dto.category.CategoryResponse;
import br.com.juntaai.dto.transaction.TransactionRequest;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransactionIntegrationTest extends AbstractIntegrationTest {

    private UUID createCategory(String token, String name, CategoryType type) throws Exception {
        String response = mockMvc.perform(post("/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CategoryRequest(name, type))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(response, CategoryResponse.class).id();
    }

    @Test
    void deveCriarEListarTransacao() throws Exception {
        String token = registerAndGetAccessToken("Duda", uniqueEmail("duda"), "senhaForte123");
        UUID categoriaId = createCategory(token, "Alimentação", CategoryType.EXPENSE);

        TransactionRequest request = new TransactionRequest(
                categoriaId, TransactionType.EXPENSE, "Mercado", new BigDecimal("120.50"), LocalDate.now());

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(120.50))
                .andExpect(jsonPath("$.categoryName").value("Alimentação"));

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + token)
                        .param("from", LocalDate.now().minusDays(1).toString())
                        .param("to", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].description").value("Mercado"));
    }

    @Test
    void naoDeveCriarTransacaoComCategoriaDeOutroUsuario() throws Exception {
        String tokenDono = registerAndGetAccessToken("Dono2", uniqueEmail("dono2"), "senhaForte123");
        UUID categoriaDoDono = createCategory(tokenDono, "Lazer", CategoryType.EXPENSE);

        String tokenIntruso = registerAndGetAccessToken("Intruso2", uniqueEmail("intruso2"), "senhaForte123");

        TransactionRequest request = new TransactionRequest(
                categoriaDoDono, TransactionType.EXPENSE, "Tentativa", new BigDecimal("50.00"), LocalDate.now());

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + tokenIntruso)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void naoDeveAceitarValorNegativoOuZero() throws Exception {
        String token = registerAndGetAccessToken("Val", uniqueEmail("val"), "senhaForte123");
        UUID categoriaId = createCategory(token, "Outros", CategoryType.EXPENSE);

        TransactionRequest request = new TransactionRequest(
                categoriaId, TransactionType.EXPENSE, "Inválida", BigDecimal.ZERO, LocalDate.now());

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
