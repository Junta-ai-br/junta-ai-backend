package br.com.juntaai.service;
import br.com.juntaai.dto.conversation.*;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.repository.*;
import br.com.juntaai.exception.AiServiceUnavailableException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** Orchestration fakes; real commit/concurrency is tested by ActionExecutionPostgresTest. */
class ConversationServiceTest {
    private final ConversationTurnStore turns = mock(ConversationTurnStore.class);
    private final ActionExecutionService executions = mock(ActionExecutionService.class);
    private final AiClient ai = mock(AiClient.class);
    private final UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
    private final ConversationService service = new ConversationService(mock(ConversationRepository.class),
            mock(MessageRepository.class), mock(UserRepository.class), ai, turns, executions);

    private ConversationTurnStore.Turn prepare() {
        var turn = new ConversationTurnStore.Turn(UUID.randomUUID(), null, null, null, ConversationContext.of(List.of()), null);
        when(turns.begin(user, conversation, "synthetic")).thenReturn(turn);
        return turn;
    }
    private ProcessingResult result(AIServiceRequest req, PendingOperation pending, String reason) {
        return new ProcessingResult(req.request_id(), req.contract_version(), null,
                new Decision(DecisionType.CLARIFICATION, reason, List.of()), null, pending,
                new ConversationalResponse("synthetic", List.of()), null, null);
    }
    @Test void clarificationPersistsPendingWithoutFinancialCall() {
        var turn = prepare();
        var pending = new PendingOperation(PendingState.AMOUNT, Operation.EXPENSE, null, List.of("monetary_value"), null);
        when(ai.process(any())).thenAnswer(inv -> result(inv.getArgument(0), pending, "validation_required"));
        service.sendMessage(user, conversation, new ChatMessageRequest("synthetic"));
        verify(turns).finish(user, conversation, turn, "synthetic", pending, true, null, false, false);
        verify(executions, never()).execute(any(), any(), any(), any(), any(), any());
    }
    @Test void foreignResultDoesNotExecuteOrFinish() {
        prepare();
        when(ai.process(any())).thenReturn(result(new AIServiceRequest("foreign", "1", "foreign", "synthetic", null, null, null, null, null), null, null));
        assertThatThrownBy(() -> service.sendMessage(user, conversation, new ChatMessageRequest("synthetic")))
                .isInstanceOf(AiServiceUnavailableException.class).hasMessage("ai_result_invalid");
        verify(executions, never()).execute(any(), any(), any(), any(), any(), any());
        verify(turns, never()).finish(any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), anyBoolean());
    }
    @Test void nonCompletionKeepsPendingByDefault() {
        var turn = prepare();
        when(ai.process(any())).thenAnswer(inv -> result(inv.getArgument(0), null, "infrastructure_unavailable"));
        service.sendMessage(user, conversation, new ChatMessageRequest("synthetic"));
        verify(turns).finish(user, conversation, turn, "synthetic", null, false, null, false, false);
        verify(executions, never()).execute(any(), any(), any(), any(), any(), any());
    }
}
