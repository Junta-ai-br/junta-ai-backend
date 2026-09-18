package br.com.juntaai.controller;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.category.CategoryRequest;
import br.com.juntaai.dto.category.CategoryResponse;
import br.com.juntaai.entity.CategoryType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryIntegrationTest extends AbstractIntegrationTest {

    private UUID createCategory(String token, String name, CategoryType type) throws Exception {
        String body = objectMapper.writeValueAsString(new CategoryRequest(name, type));

        String response = mockMvc.perform(post("/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(response, CategoryResponse.class).id();
    }

    @Test
    void deveCriarListarAtualizarERemoverCategoria() throws Exception {
        String token = registerAndGetAccessToken("Bia", uniqueEmail("bia"), "senhaForte123");

        UUID id = createCategory(token, "Alimentação", CategoryType.EXPENSE);

        mockMvc.perform(get("/categories").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Alimentação"));

        mockMvc.perform(put("/categories/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CategoryRequest("Alimentação e Bebidas", CategoryType.EXPENSE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alimentação e Bebidas"));

        mockMvc.perform(delete("/categories/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/categories").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void naoDevePermitirNomeDuplicadoParaOMesmoUsuario() throws Exception {
        String token = registerAndGetAccessToken("Carlos", uniqueEmail("carlos"), "senhaForte123");
        createCategory(token, "Transporte", CategoryType.EXPENSE);

        mockMvc.perform(post("/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CategoryRequest("Transporte", CategoryType.EXPENSE))))
                .andExpect(status().isConflict());
    }

    @Test
    void usuarioNaoDeveConseguirEditarOuRemoverCategoriaDeOutroUsuario() throws Exception {
        String tokenDono = registerAndGetAccessToken("Dono", uniqueEmail("dono"), "senhaForte123");
        String tokenIntruso = registerAndGetAccessToken("Intruso", uniqueEmail("intruso"), "senhaForte123");

        UUID categoriaDoDono = createCategory(tokenDono, "Salário", CategoryType.INCOME);

        // O intruso nem vê a categoria do dono na própria listagem...
        mockMvc.perform(get("/categories").header("Authorization", "Bearer " + tokenIntruso))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // ...e não consegue editar...
        mockMvc.perform(put("/categories/" + categoriaDoDono)
                        .header("Authorization", "Bearer " + tokenIntruso)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CategoryRequest("Hackeado", CategoryType.INCOME))))
                .andExpect(status().isNotFound());

        // ...nem remover a categoria de outro usuário.
        mockMvc.perform(delete("/categories/" + categoriaDoDono)
                        .header("Authorization", "Bearer " + tokenIntruso))
                .andExpect(status().isNotFound());
    }

    @Test
    void naoDeveAcessarCategoriasSemToken() throws Exception {
        mockMvc.perform(get("/categories")).andExpect(status().isUnauthorized());
    }
}
