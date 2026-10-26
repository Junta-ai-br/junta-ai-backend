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
    @Test
    void authenticatedCreationDeletionIsolationAndRetries() throws Exception {
        String a = registerAndGetAccessToken("User A", uniqueEmail("goal-a"), "synthetic");
        String b = registerAndGetAccessToken("User B", uniqueEmail("goal-b"), "synthetic");
        UUID key = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new GoalRequest("Reserva", new BigDecimal("200.01"), null));
        String first = mockMvc.perform(post("/goals").header("Authorization", "Bearer " + a).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id = objectMapper.readValue(first, GoalResponse.class).id();
        mockMvc.perform(post("/goals").header("Authorization", "Bearer " + a).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        mockMvc.perform(get("/goals").header("Authorization", "Bearer " + b)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(delete("/goals/" + id).header("Authorization", "Bearer " + b).header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isNotFound());
        UUID deletion = UUID.randomUUID();
        for (int i=0; i<2; i++) mockMvc.perform(delete("/goals/" + id).header("Authorization", "Bearer " + a).header("Idempotency-Key", deletion)).andExpect(status().isNoContent());
        mockMvc.perform(get("/goals").header("Authorization", "Bearer " + a)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post("/goals").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/goals").header("Authorization", "Bearer " + a).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    private UUID createGoal(String token, BigDecimal target) throws Exception {
        String response = mockMvc.perform(post("/goals")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
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
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GoalProgressRequest(new BigDecimal("400.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.currentAmount").value(400.00));

        mockMvc.perform(patch("/goals/" + goalId + "/progress")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
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
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GoalProgressRequest(new BigDecimal("100.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(patch("/goals/" + goalId + "/progress")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GoalProgressRequest(new BigDecimal("10.00")))))
                .andExpect(status().isBadRequest());
    }
}
