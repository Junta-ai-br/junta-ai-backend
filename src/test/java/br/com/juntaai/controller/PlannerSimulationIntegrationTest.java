package br.com.juntaai.controller;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.repository.PlannerSimulationRepository;
import br.com.juntaai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlannerSimulationIntegrationTest extends AbstractIntegrationTest {
    private static final String URL = "/planner/simulations";
    private static final String BODY = """
            {"goalName":"Viagem","goalAmount":12000,"deadlineMonths":12,
             "analysisBase":"last_3_months","monthlyAmount":1000,"estimatedMonths":12,
             "historyContext":{"income":4000,"fixedExpenses":1000,"totalExpenses":3000,
                               "available":1000,"commitmentRate":75}}
            """;
    @Autowired PlannerSimulationRepository simulations;
    @Autowired UserRepository users;

    @Test
    void persistsSnapshotForAuthenticatedOwnerAndIsolatesListAndDelete() throws Exception {
        String email = uniqueEmail("planner-owner");
        String owner = registerAndGetAccessToken("Owner", email, "11999999999");
        String other = registerAndGetAccessToken("Other", uniqueEmail("planner-other"), "11999999998");
        UUID ownerId = users.findByEmail(email).orElseThrow().getId();
        // An extra client userId must never override the authenticated owner.
        String body = BODY.replace("\"goalName\"", "\"userId\":\"" + UUID.randomUUID() + "\",\"goalName\"");
        var response = mockMvc.perform(post(URL).header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(response).get("id").asText());
        assertThat(simulations.findByIdAndUserId(id, ownerId)).isPresent();

        mockMvc.perform(get(URL).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].historyContext.available").value(1000));
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(delete(URL + "/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        assertThat(simulations.existsById(id)).isTrue();
        mockMvc.perform(delete(URL + "/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        assertThat(simulations.existsById(id)).isFalse();
        mockMvc.perform(delete(URL + "/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void acceptsSimulationWithoutHistory() throws Exception {
        String token = registerAndGetAccessToken("Planner", uniqueEmail("planner-empty"), "11999999999");
        var payload = objectMapper.readTree(BODY).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("historyContext");
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("analysisBase", "no_history");
        mockMvc.perform(post(URL).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisBase").value("no_history"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"goalName\":\" \"}", "{\"goalAmount\":0}",
            "{\"goalAmount\":1.001}", "{\"monthlyAmount\":-1}", "{\"deadlineMonths\":0}",
            "{\"deadlineMonths\":1201}", "{\"estimatedMonths\":12001}",
            "{\"analysisBase\":\"unknown\"}", "{\"historyContext\":{\"income\":-1}}"})
    void rejectsInvalidPayloads(String changes) throws Exception {
        String token = registerAndGetAccessToken("Invalid", uniqueEmail("planner-invalid"), "11999999999");
        var payload = objectMapper.readTree(BODY);
        if (changes.equals("{}")) payload = objectMapper.readTree(changes);
        else ((com.fasterxml.jackson.databind.node.ObjectNode) payload)
                .setAll((com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(changes));
        mockMvc.perform(post(URL).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresAuthenticationForAllOperations() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(URL + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void limitsSavedSimulationsPerUser() throws Exception {
        String email = uniqueEmail("planner-cap");
        String token = registerAndGetAccessToken("Cap", email, "11999999999");
        var user = users.findByEmail(email).orElseThrow();
        for (int i = 0; i < 200; i++) {
            var simulation = new br.com.juntaai.entity.PlannerSimulation();
            simulation.setUser(user);
            simulation.setGoalName("Meta " + i);
            simulation.setGoalAmount(new java.math.BigDecimal("1000.00"));
            simulation.setDeadlineMonths(10);
            simulation.setAnalysisBase("no_history");
            simulation.setMonthlyAmount(new java.math.BigDecimal("100.00"));
            simulation.setEstimatedMonths(10);
            simulations.save(simulation);
        }
        mockMvc.perform(post(URL).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }
}
