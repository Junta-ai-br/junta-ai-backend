package br.com.juntaai.controller;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.category.CategoryRequest;
import br.com.juntaai.dto.category.CategoryResponse;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.dto.conversation.ConversationResponse;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.ActionRequest;
import br.com.juntaai.integration.ai.model.ConversationalResponse;
import br.com.juntaai.integration.ai.model.Decision;
import br.com.juntaai.integration.ai.model.DecisionType;
import br.com.juntaai.integration.ai.model.Operation;
import br.com.juntaai.integration.ai.model.ParsedFinancialData;
import br.com.juntaai.integration.ai.model.PendingOperation;
import br.com.juntaai.integration.ai.model.PendingState;
import br.com.juntaai.integration.ai.model.ProcessingResult;
import br.com.juntaai.integration.ai.model.TemporalStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testa a integração Backend <-> IA de ponta a ponta via HTTP, com o
 * AiClient substituído por um mock (@MockBean) — sem depender do serviço
 * junta-ai-ai estar no ar. Segue o contrato real de
 * specs/001-ai-service-foundation/contracts/service-boundary.md.
 */
class ConversationIntegrationTest extends AbstractIntegrationTest {

    @MockBean
    private AiClient aiClient;

    private UUID createCategory(String token, String name, CategoryType type) throws Exception {
        String response = mockMvc.perform(post("/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CategoryRequest(name, type))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(response, CategoryResponse.class).id();
    }

    private UUID createConversation(String token) throws Exception {
        String response = mockMvc.perform(post("/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Gastos de setembro"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readValue(response, ConversationResponse.class).id();
    }

    @Test
    void deveExecutarAcaoECriarTransacaoQuandoIaRetornaActionRequest() throws Exception {
        String token = registerAndGetAccessToken("Ia1", uniqueEmail("ia1"), "senhaForte123");
        createCategory(token, "Alimentação", CategoryType.EXPENSE);
        UUID conversationId = createConversation(token);

        ParsedFinancialData payload = new ParsedFinancialData(
                new BigDecimal("80.00"), "Mercado", "Alimentação", "hoje",
                LocalDate.now().toString(), null, Operation.EXPENSE, TemporalStatus.REALIZED, null);

        ActionRequest action = new ActionRequest(
                UUID.randomUUID().toString(), Operation.EXPENSE, payload, "authoritative_state", null);

        ProcessingResult actionResult = new ProcessingResult(
                "req-1", "1", null,
                new Decision(DecisionType.ACTION_REQUEST, null, List.of()),
                action, null, null, null, null);

        ProcessingResult confirmedResult = new ProcessingResult(
                "req-2", "1", null,
                new Decision(DecisionType.RESPONSE, "execution_confirmed", List.of()),
                null, null,
                new ConversationalResponse("Anotei: R$ 80,00 em Alimentação.", List.of()),
                null, null);

        when(aiClient.process(any())).thenReturn(actionResult).thenReturn(confirmedResult);

        mockMvc.perform(post("/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatMessageRequest("gastei 80 no mercado"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionExecuted").value(true))
                .andExpect(jsonPath("$.assistantMessage.content").value("Anotei: R$ 80,00 em Alimentação."));

        mockMvc.perform(get("/transactions")
                        .header("Authorization", "Bearer " + token)
                        .param("from", LocalDate.now().minusDays(1).toString())
                        .param("to", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].amount").value(80.00));
    }

    @Test
    void deveGuardarEstadoPendenteQuandoIaSolicitaClarificacao() throws Exception {
        String token = registerAndGetAccessToken("Ia2", uniqueEmail("ia2"), "senhaForte123");
        createCategory(token, "Alimentação", CategoryType.EXPENSE);
        UUID conversationId = createConversation(token);

        PendingOperation pending = new PendingOperation(
                PendingState.CLARIFICATION, Operation.EXPENSE,
                new ParsedFinancialData(null, null, null, null, null, null, null, TemporalStatus.UNRESOLVED, null),
                List.of("monetary_value"), null);

        ProcessingResult clarificationResult = new ProcessingResult(
                "req-1", "1", null,
                new Decision(DecisionType.CLARIFICATION, "validation_required", List.of("monetary_value")),
                null, pending,
                new ConversationalResponse("Quanto você gastou?", List.of()),
                null, null);

        when(aiClient.process(any())).thenReturn(clarificationResult);

        mockMvc.perform(post("/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatMessageRequest("gastei no mercado"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionExecuted").value(false))
                .andExpect(jsonPath("$.assistantMessage.content").value("Quanto você gastou?"));
    }

    @Test
    void naoDeveEnviarMensagemParaConversaDeOutroUsuario() throws Exception {
        String tokenDono = registerAndGetAccessToken("Dono3", uniqueEmail("dono3"), "senhaForte123");
        UUID conversationId = createConversation(tokenDono);

        String tokenIntruso = registerAndGetAccessToken("Intruso3", uniqueEmail("intruso3"), "senhaForte123");

        mockMvc.perform(post("/conversations/" + conversationId + "/messages")
                        .header("Authorization", "Bearer " + tokenIntruso)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChatMessageRequest("oi"))))
                .andExpect(status().isNotFound());
    }
}
