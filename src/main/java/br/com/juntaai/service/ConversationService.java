package br.com.juntaai.service;
import br.com.juntaai.dto.conversation.*;
import br.com.juntaai.entity.*;
import br.com.juntaai.exception.ApiException;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.exception.MessageEntryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
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

    private static final String RECOVERY_NOTICE = "Sua nova mensagem não foi processada. Esta resposta se refere à operação anterior. "
            + "Para processar a nova intenção, envie-a novamente após a conclusão desta recuperação. ";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final AiClient aiClient;
    private final ConversationTurnStore turns;
    private final ActionExecutionService executions;
    private final MessageEntryStore entries;
    private final ObjectMapper mapper;

    @Transactional
    public ConversationResponse createConversation(UUID userId, String title) {
        if (title != null && title.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "O título deve ter no máximo 200 caracteres.");
        }
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
    public ChatReplyResponse sendMessage(UUID userId, UUID conversationId, ChatMessageRequest request, UUID key) {
        if (key == null || key.version() != 4 || key.variant() != 2) {
            throw new MessageEntryException(HttpStatus.BAD_REQUEST, "idempotency_key_invalid");
        }
        var admission = entries.admit(userId, conversationId, key, request.message());
        var entry = admission.entry();
        var turn = admission.turn();
        if ("FAILED".equals(entry.state())) throw MessageEntryException.failed();
        if ("COMPLETED".equals(entry.state())) {
            if (entry.receiptId() != null) executions.recordedReceipt(userId, conversationId, entry.receiptId());
            return contextual(turns.completed(userId, conversationId, turn.messageId()), entry.recovering());
        }
        if ("RECEIPT_PENDING".equals(entry.state())) {
            return composeReceipt(userId, conversationId, turn,
                    executions.recordedReceipt(userId, conversationId, entry.receiptId()), entry.recovering());
        }
        if ("ACTION_SELECTED".equals(entry.state())) return resumeSelected(userId, conversationId, admission);
        if (!admission.fresh()) {
            throw new MessageEntryException(HttpStatus.CONFLICT, "message_processing_in_progress");
        }

        // Only the admission winner can interpret. A retry never enters this branch.
        var firstCall = new AIServiceRequest(entry.requestId(), "1", conversationId.toString(),
                request.message(), turn.context(), turn.pending(), null, turn.snapshot(), null);
        try {
            ProcessingResult result = correlated(firstCall, aiClient.process(firstCall));
            if (result.decision().type() == br.com.juntaai.integration.ai.model.DecisionType.ACTION_REQUEST) {
                if (!entries.select(userId, conversationId, turn.messageId(), result.action_request())) {
                    throw MessageEntryException.failed();
                }
            } else {
                if (result.action_request() != null) throw new br.com.juntaai.exception.AiServiceUnavailableException("ai_result_invalid");
                boolean replace = result.pending_operation() != null || "pending_operation_cancelled".equals(result.decision().reason());
                String reply = result.response() == null || result.response().message() == null || result.response().message().isBlank()
                        ? "Preciso de mais informações para continuar." : result.response().message();
                var response = entries.completeResponse(userId, conversationId, turn, reply, result.pending_operation(), replace);
                if (response == null) throw MessageEntryException.failed();
                return response;
            }
        } catch (RuntimeException ex) {
            // Guarded update can only terminate PROCESSING, never a selected/committed entry.
            if (!entries.failInitial(userId, conversationId, turn.messageId())) {
                // A commit whose response was lost may already have selected/finalized the entry.
                return resumeSelected(userId, conversationId, entries.admit(userId, conversationId, key, request.message()));
            }
            throw MessageEntryException.failed();
        }
        // Reload the persisted selection, not the mutable AI result.
        return resumeSelected(userId, conversationId, entries.admit(userId, conversationId, key, request.message()));
    }

    private ChatReplyResponse resumeSelected(UUID userId, UUID conversationId, MessageEntryStore.Admission admission) {
        var entry = admission.entry();
        if ("RECEIPT_PENDING".equals(entry.state())) {
            return composeReceipt(userId, conversationId, admission.turn(),
                    executions.recordedReceipt(userId, conversationId, entry.receiptId()), entry.recovering());
        }
        if ("COMPLETED".equals(entry.state())) {
            if (entry.receiptId() != null) executions.recordedReceipt(userId, conversationId, entry.receiptId());
            return contextual(turns.completed(userId, conversationId, entry.messageId()), entry.recovering());
        }
        ActionRequest action;
        try { action = mapper.readValue(entry.actionJson(), ActionRequest.class); }
        catch (Exception ex) { throw new br.com.juntaai.exception.BusinessRuleException("action_entry_mismatch"); }
        ActionExecutionService.Receipt receipt;
        try {
            receipt = executions.execute(userId, conversationId, entry.messageId(), entry.requestId(), action, entry.pendingBefore());
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ex) {
            throw new br.com.juntaai.exception.AiServiceUnavailableException("execution_result_unconfirmed");
        }
        return composeReceipt(userId, conversationId, admission.turn(), receipt, false);
    }

    private ChatReplyResponse composeReceipt(UUID userId, UUID conversationId,
                                            ConversationTurnStore.Turn turn, ActionExecutionService.Receipt receipt, boolean recovering) {
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
        ChatReplyResponse result = turns.finish(userId, conversationId, turn, reply, null, clearPending,
                receipt.actionId(), complete, executed && !recovering);
        return contextual(result, recovering);
    }

    private ChatReplyResponse contextual(ChatReplyResponse result, boolean recovering) {
        if (!recovering) return result;
        // Persist only the operation's composition/fallback. This call's trusted flow determines
        // the contextual HTTP notice, independently of which turn finalized the receipt.
        MessageResponse accepted = result.assistantMessage();
        return new ChatReplyResponse(result.conversationId(), result.userMessage(),
                new MessageResponse(accepted.id(), accepted.role(), RECOVERY_NOTICE + accepted.content(), accepted.createdAt()), false);
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
