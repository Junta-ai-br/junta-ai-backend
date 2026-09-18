package br.com.juntaai.controller;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.goal.GoalProgressRequest;
import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.dto.goal.GoalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoalIntegrationTest extends AbstractIntegrationTest {

    private UUID createGoal(String token, BigDecimal target) throws Exception {
        String response = mockMvc.perform(post("/goals")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GoalRequest("Viagem", target, null))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(response, GoalResponse.class).id();
    }

    @Test
    void deveAcumularProgressoEConcluirMetaAoAtingirValorAlvo() throws Exception {
        String token = registerAndGetAccessToken("Gui", uniqueEmail("gui"), "senhaForte123");
        UUID goalId = createGoal(token, new BigDecimal("1000.00"));

        mockMvc.perform(patch("/goals/" + goalId + "/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GoalProgressRequest(new BigDecimal("400.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.currentAmount").value(400.00));

        mockMvc.perform(patch("/goals/" + goalId + "/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GoalProgressRequest(new BigDecimal("600.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void naoDeveAceitarNovoAporteAposMetaConcluida() throws Exception {
        String token = registerAndGetAccessToken("Lia", uniqueEmail("lia"), "senhaForte123");
        UUID goalId = createGoal(token, new BigDecimal("100.00"));

        mockMvc.perform(patch("/goals/" + goalId + "/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GoalProgressRequest(new BigDecimal("100.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(patch("/goals/" + goalId + "/progress")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GoalProgressRequest(new BigDecimal("10.00")))))
                .andExpect(status().isBadRequest());
    }
}
