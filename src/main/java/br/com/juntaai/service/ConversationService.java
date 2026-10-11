package br.com.juntaai.service;
import br.com.juntaai.dto.conversation.*;
import br.com.juntaai.entity.*;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final AiClient aiClient;
    private final ConversationTurnStore turns;
    private final ActionExecutionService executions;

    @Transactional
    public ConversationResponse createConversation(UUID userId, String title) {
        User user = userRepository.getReferenceById(userId);
        Conversation conversation = Conversation.builder().user(user).title(title).build();
        conversationRepository.save(conversation);
        return toResponse(conversation);
    }

    public List<ConversationResponse> listForUser(UUID userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<MessageResponse> listMessages(UUID userId, UUID conversationId) {
        findOwnedOrThrow(userId, conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(this::toResponse)
                .toList();
    }

    // No encompassing transaction: both AI calls are outside financial commit.
    public ChatReplyResponse sendMessage(UUID userId, UUID conversationId, ChatMessageRequest request) {
        var turn = turns.begin(userId, conversationId, request.message());
        var receipt = executions.pendingReceipt(userId, conversationId);
        if (receipt != null) return composeReceipt(userId, conversationId, turn, receipt);

        var firstCall = new AIServiceRequest(UUID.randomUUID().toString(), "1", conversationId.toString(),
                request.message(), turn.context(), turn.pending(), null, turn.snapshot(), null);
        ProcessingResult result = correlated(firstCall, aiClient.process(firstCall));
        if (result.decision().type() == br.com.juntaai.integration.ai.model.DecisionType.ACTION_REQUEST) {
            try {
                receipt = executions.execute(userId, conversationId, turn.messageId(), firstCall.request_id(),
                        result.action_request(), turn.pendingBefore());
            } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ex) {
                // Proxy has rolled back or outcome is uncertain: never leak SQL/payload or rerun here.
                throw new br.com.juntaai.exception.AiServiceUnavailableException("execution_result_unconfirmed");
            }
            return composeReceipt(userId, conversationId, turn, receipt);
        }
        if (result.action_request() != null) throw new br.com.juntaai.exception.AiServiceUnavailableException("ai_result_invalid");
        boolean replace = result.pending_operation() != null
                || "pending_operation_cancelled".equals(result.decision().reason());
        String reply = result.response() == null || result.response().message() == null || result.response().message().isBlank()
                ? "Preciso de mais informações para continuar." : result.response().message();
        return turns.finish(userId, conversationId, turn, reply, result.pending_operation(), replace, null, false, false);
    }

    private ChatReplyResponse composeReceipt(UUID userId, UUID conversationId,
                                            ConversationTurnStore.Turn turn, ActionExecutionService.Receipt receipt) {
        boolean executed = receipt.confirmation().status() == ExecutionStatus.SUCCESSFUL;
        var confirmCall = new AIServiceRequest(UUID.randomUUID().toString(), "1", conversationId.toString(),
                receipt.originalMessage(), turn.context(), null, receipt.action(), null, receipt.confirmation());
        String reply = executed ? "A operação foi registrada. A resposta do assistente está pendente; retome esta conversa."
                : "A operação não foi executada. A resposta do assistente está pendente; retome esta conversa.";
        boolean complete = false;
        try {
            ProcessingResult composed = correlated(confirmCall, aiClient.process(confirmCall));
            complete = composed.action_request() == null && composed.decision().type() == br.com.juntaai.integration.ai.model.DecisionType.RESPONSE
                    && (executed ? "execution_confirmed".equals(composed.decision().reason())
                                 : "execution_confirmation_not_matching".equals(composed.decision().reason()));
            if (complete && composed.response() != null && composed.response().message() != null
                    && !composed.response().message().isBlank()) reply = composed.response().message();
            else complete = false;
        } catch (br.com.juntaai.exception.AiServiceUnavailableException ignored) {
            // Financial result was committed before this call; leave durable composition pending.
        }
        // Only clear the pending operation if the same state still belongs to this execution.
        boolean clearPending = complete && executed && java.util.Objects.equals(turn.pendingBefore(), receipt.pendingStateBefore());
        return turns.finish(userId, conversationId, turn, reply, null, clearPending,
                receipt.actionId(), complete, executed);
    }

    private ProcessingResult correlated(AIServiceRequest sent, ProcessingResult received) {
        if (received == null || received.decision() == null || !sent.request_id().equals(received.request_id())
                || !sent.contract_version().equals(received.contract_version())) {
            throw new br.com.juntaai.exception.AiServiceUnavailableException("ai_result_invalid");
        }
        return received;
    }

    private Conversation findOwnedOrThrow(UUID userId, UUID conversationId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversa"));
    }

    private ConversationResponse toResponse(Conversation c) {
        return new ConversationResponse(c.getId(), c.getTitle(), c.getUpdatedAt());
    }

    private MessageResponse toResponse(Message m) {
        return new MessageResponse(m.getId(), m.getRole().name(), m.getContent(), m.getCreatedAt());
    }

}
