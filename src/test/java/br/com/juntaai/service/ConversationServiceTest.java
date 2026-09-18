package br.com.juntaai.service;

import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.dto.dashboard.DashboardResponse;
import br.com.juntaai.entity.Category;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.entity.Conversation;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.ActionRequest;
import br.com.juntaai.integration.ai.model.ConversationalResponse;
import br.com.juntaai.integration.ai.model.Decision;
import br.com.juntaai.integration.ai.model.DecisionType;
import br.com.juntaai.integration.ai.model.ExecutionStatus;
import br.com.juntaai.integration.ai.model.Operation;
import br.com.juntaai.integration.ai.model.ParsedFinancialData;
import br.com.juntaai.integration.ai.model.PendingOperation;
import br.com.juntaai.integration.ai.model.PendingState;
import br.com.juntaai.integration.ai.model.ProcessingResult;
import br.com.juntaai.integration.ai.model.TemporalStatus;
import br.com.juntaai.repository.CategoryRepository;
import br.com.juntaai.repository.ConversationRepository;
import br.com.juntaai.repository.GoalRepository;
import br.com.juntaai.repository.MessageRepository;
import br.com.juntaai.repository.TransactionRepository;
import br.com.juntaai.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Testa a orquestração Backend <-> IA isoladamente: AiClient é mockado,
 * então nenhuma chamada de rede acontece. Cobre exatamente o contrato de
 * specs/001-ai-service-foundation/contracts/service-boundary.md
 * (repositório junta-ai-ai): decisão CLARIFICATION guarda estado
 * pendente sem executar nada; decisão ACTION_REQUEST executa via
 * TransactionService e faz a segunda chamada de confirmação.
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private GoalRepository goalRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AiClient aiClient;
    @Mock private TransactionService transactionService;
    @Mock private GoalService goalService;
    @Mock private DashboardService dashboardService;

    private ConversationService conversationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                conversationRepository, messageRepository, userRepository, categoryRepository,
                goalRepository, transactionRepository, aiClient, transactionService, goalService,
                dashboardService, new ObjectMapper());
    }

    private void stubCommonFixtures(Conversation conversation) {
        when(conversationRepository.findByIdAndUserId(conversationId, userId))
                .thenReturn(Optional.of(conversation));
        when(messageRepository.save(any())).thenAnswer(inv -> {
            var m = inv.getArgument(0, br.com.juntaai.entity.Message.class);
            m.setId(UUID.randomUUID());
            return m;
        });
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of());
        when(dashboardService.summarize(any(), any(), any())).thenReturn(new DashboardResponse(
                LocalDate.now().minusDays(90), LocalDate.now(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        when(transactionRepository.findByUserIdAndTransactionDateBetween(any(), any(), any(), any()))
                .thenReturn(Page.empty());
        when(goalRepository.findByUserIdOrderByDeadlineAsc(userId)).thenReturn(List.of());
    }

    @Test
    void deveGuardarEstadoPendenteQuandoIaSolicitaClarificacao() {
        Conversation conversation = Conversation.builder().build();
        conversation.setId(conversationId);
        stubCommonFixtures(conversation);

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

        var reply = conversationService.sendMessage(userId, conversationId, new ChatMessageRequest("gastei no mercado"));

        assertThat(reply.actionExecuted()).isFalse();
        assertThat(reply.assistantMessage().content()).isEqualTo("Quanto você gastou?");
        assertThat(conversation.getPendingState()).contains("pending_clarification");
    }

    @Test
    void deveExecutarTransacaoQuandoIaRetornaActionRequest() {
        Conversation conversation = Conversation.builder().build();
        conversation.setId(conversationId);
        stubCommonFixtures(conversation);

        Category category = Category.builder().name("Alimentação").type(CategoryType.EXPENSE).build();
        category.setId(UUID.randomUUID());
        when(categoryRepository.findByUserIdOrderByNameAsc(userId)).thenReturn(List.of(category));

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

        var reply = conversationService.sendMessage(userId, conversationId, new ChatMessageRequest("gastei 80 no mercado"));

        assertThat(reply.actionExecuted()).isTrue();
        assertThat(reply.assistantMessage().content()).isEqualTo("Anotei: R$ 80,00 em Alimentação.");
    }

    @Test
    void naoDeveMarcarComoExecutadoQuandoCategoriaNaoExiste() {
        Conversation conversation = Conversation.builder().build();
        conversation.setId(conversationId);
        stubCommonFixtures(conversation);
        when(categoryRepository.findByUserIdOrderByNameAsc(userId)).thenReturn(List.of());

        ParsedFinancialData payload = new ParsedFinancialData(
                new BigDecimal("80.00"), "Mercado", "Categoria Inexistente", "hoje",
                LocalDate.now().toString(), null, Operation.EXPENSE, TemporalStatus.REALIZED, null);

        ActionRequest action = new ActionRequest(
                UUID.randomUUID().toString(), Operation.EXPENSE, payload, "authoritative_state", null);

        ProcessingResult actionResult = new ProcessingResult(
                "req-1", "1", null,
                new Decision(DecisionType.ACTION_REQUEST, null, List.of()),
                action, null, null, null, null);

        ProcessingResult failedResult = new ProcessingResult(
                "req-2", "1", null,
                new Decision(DecisionType.RESPONSE, "execution_confirmation_not_matching", List.of()),
                null, null,
                new ConversationalResponse("Não encontrei essa categoria.", List.of()),
                null, null);

        when(aiClient.process(any())).thenReturn(actionResult).thenReturn(failedResult);

        var reply = conversationService.sendMessage(userId, conversationId, new ChatMessageRequest("gastei 80 em algo"));

        assertThat(reply.actionExecuted()).isFalse();
    }
}
