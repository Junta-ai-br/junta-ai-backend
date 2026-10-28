package br.com.juntaai.service;

import br.com.juntaai.entity.*;
import br.com.juntaai.exception.BusinessRuleException;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Every repository, financial write and snapshot is mocked. No real contributions. */
class GoalContributionConfirmationTest {
    final UUID user = UUID.randomUUID(), conversationId = UUID.randomUUID(), messageId = UUID.randomUUID(), goalId = UUID.randomUUID();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final ConversationRepository conversations = mock(ConversationRepository.class);
    final MessageRepository messages = mock(MessageRepository.class);
    final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
    final GoalRepository goals = mock(GoalRepository.class);
    final GoalService goalService = mock(GoalService.class);
    final TransactionService transactions = mock(TransactionService.class);
    final ConversationTurnStore turns = mock(ConversationTurnStore.class);
    final Map<String, ActionExecution> receipts = new HashMap<>();
    final Conversation conversation = Conversation.builder().build();
    final Message message = Message.builder().conversation(conversation).role(MessageRole.USER).build();
    final Goal goal = Goal.builder().name("Viagem").currentAmount(BigDecimal.ZERO).targetAmount(new BigDecimal("1000.00")).status(GoalStatus.IN_PROGRESS).build();
    final ActionExecutionService service = new ActionExecutionService(conversations, messages, executions,
        mock(MessageEntryRepository.class), mock(CategoryRepository.class), goals, transactions, goalService, turns,
        mapper, mock(JdbcTemplate.class), mock(jakarta.persistence.EntityManager.class), mock(jakarta.validation.Validator.class));

    @BeforeEach void setup() {
        conversation.setId(conversationId); message.setId(messageId); goal.setId(goalId);
        when(conversations.findLockedByIdAndUserId(conversationId,user)).thenReturn(Optional.of(conversation));
        when(messages.findById(messageId)).thenReturn(Optional.of(message));
        when(executions.findByActionId(any())).thenAnswer(inv -> Optional.ofNullable(receipts.get(inv.getArgument(0))));
        when(goals.findLockedByUserIdAndName(user,"Viagem")).thenReturn(List.of(goal));
        when(turns.buildFinancialSnapshot(user)).thenReturn(new FinancialStateSnapshot(null,Map.of(),Map.of(),List.of(),List.of(),Map.of(),Map.of()));
    }
    ParsedFinancialData data(String amount) {
        return new ParsedFinancialData(new BigDecimal(amount),"synthetic",null,null,null,"Viagem",Operation.GOAL_CONTRIBUTION,TemporalStatus.REALIZED,null);
    }
    ActionRequest action(ParsedFinancialData data) { return new ActionRequest(UUID.randomUUID().toString(),Operation.GOAL_CONTRIBUTION,data,"authoritative_state",null); }
    String proposal(ParsedFinancialData data, UUID target) throws Exception {
        return mapper.writeValueAsString(new PendingOperation(PendingState.GOAL,Operation.GOAL_CONTRIBUTION,data,List.of("confirmation"),target.toString()));
    }
    ActionExecutionService.Receipt execute(ActionRequest action, String pending, String reply) throws Exception {
        conversation.setPendingState(pending); message.setContent(reply);
        if (!receipts.containsKey(action.action_id())) {
            var receipt = new ActionExecution(); receipt.setActionId(action.action_id()); receipt.setUserId(user);
            receipt.setConversationId(conversationId); receipt.setUserMessageId(messageId); receipt.setRequestId("synthetic");
            receipt.setActionJson(mapper.writeValueAsString(action)); receipt.setPendingStateBefore(pending);
            receipts.put(action.action_id(),receipt);
        }
        return service.execute(user,conversationId,messageId,"synthetic",action,pending);
    }
    void rejected(ActionExecutionService.Receipt receipt) {
        assertThat(receipt.confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
        verifyNoInteractions(goalService,transactions);
    }
    @Test void directInstructionAndBareConfirmationWithoutProposalCannotWrite() throws Exception {
        rejected(execute(action(data("100.00")),null,"Guardei 100 para viagem"));
        rejected(execute(action(data("100.00")),null,"sim"));
        verifyNoInteractions(goals);
    }
    @Test void confirmedMatchingProposalUsesTheOwnedGoalAndUpdatedSnapshot() throws Exception {
        var payload=data("100.00"); var reply=execute(action(payload),proposal(payload,goalId),"sim");
        assertThat(reply.confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        assertThat(reply.confirmation().authoritative_state()).isNotNull();
        verify(goalService).addProgress(user,goalId,new BigDecimal("100.00"));
        verifyNoInteractions(transactions);
    }
    @Test void changedAmountCannotReuseConsent() throws Exception {
        rejected(execute(action(data("200.00")),proposal(data("100.00"),goalId),"sim"));
    }
    @Test void mixedReplyIsNotExplicitConfirmation() throws Exception {
        var payload=data("100.00"); rejected(execute(action(payload),proposal(payload,goalId),"sim, mas use 200"));
    }
    @Test void missingFieldsAndWrongPendingStateCannotAuthorize() throws Exception {
        var payload=data("100.00");
        for (var pending:List.of(new PendingOperation(PendingState.GOAL,Operation.GOAL_CONTRIBUTION,payload,List.of("goal_reference"),goalId.toString()),
            new PendingOperation(PendingState.CLARIFICATION,Operation.GOAL_CONTRIBUTION,payload,List.of("confirmation"),goalId.toString()))) {
            rejected(execute(action(payload),mapper.writeValueAsString(pending),"sim"));
        }
    }
    @Test void targetIdentityCannotChangeUnderTheSameName() throws Exception {
        var payload=data("100.00"); rejected(execute(action(payload),proposal(payload,UUID.randomUUID()),"sim"));
    }
    @Test void missingOrForeignGoalCannotBeConfirmedForThisUser() throws Exception {
        var payload=data("100.00"); when(goals.findLockedByUserIdAndName(user,"Viagem")).thenReturn(List.of());
        rejected(execute(action(payload),proposal(payload,goalId),"sim"));
        verify(goals).findLockedByUserIdAndName(user,"Viagem");
    }
    @Test void homonymousGoalsCannotChooseAnArbitraryIdentity() throws Exception {
        var payload=data("100.00"); var other=Goal.builder().name("Viagem").build();other.setId(UUID.randomUUID());
        when(goals.findLockedByUserIdAndName(user,"Viagem")).thenReturn(List.of(goal,other));
        rejected(execute(action(payload),proposal(payload,goalId),"sim"));
    }
    @Test void cancelledGoalStillCannotReceiveAContribution() throws Exception {
        goal.setStatus(GoalStatus.CANCELLED);var payload=data("100.00");
        rejected(execute(action(payload),proposal(payload,goalId),"sim"));
    }
    @Test void plannedPayloadIsNotAuthorizedByAnAffirmation() throws Exception {
        var payload=new ParsedFinancialData(new BigDecimal("100.00"),"synthetic",null,null,null,"Viagem",Operation.GOAL_CONTRIBUTION,TemporalStatus.PLANNED,null);
        rejected(execute(action(payload),proposal(payload,goalId),"sim"));
    }
    @Test void invalidAmountsRemainRejectedAfterConfirmation() throws Exception {
        for(var amount:List.of("-1.00","0.00","0.001")) {
            var payload=data(amount); rejected(execute(action(payload),proposal(payload,goalId),"sim"));
        }
    }
    @Test void storedReceiptReplaysWithoutAnotherContributionAndLateYesCannotExecute() throws Exception {
        var payload=data("100.00");var action=action(payload);var pending=proposal(payload,goalId);
        var first=execute(action,pending,"sim");
        var replay=execute(action,null,"sim");
        assertThat(replay.confirmation()).isEqualTo(first.confirmation());
        assertThat(execute(action(payload),null,"sim").confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
        verify(goalService,times(1)).addProgress(user,goalId,new BigDecimal("100.00"));
        verifyNoInteractions(transactions);
    }
    @Test void repositoryFailurePropagatesWithoutRecordingSuccess() throws Exception {
        var payload=data("100.00");var action=action(payload);
        when(goals.findLockedByUserIdAndName(user,"Viagem")).thenThrow(new IllegalStateException("synthetic failure"));
        assertThatThrownBy(()->execute(action,proposal(payload,goalId),"sim")).isInstanceOf(IllegalStateException.class);
        assertThat(receipts.get(action.action_id()).getReceiptJson()).isNull();
        verifyNoInteractions(goalService,transactions);
    }
    @Test void goalWriteFailureDoesNotProduceASuccessReceipt() throws Exception {
        var payload=data("100.00");var action=action(payload);
        doThrow(new BusinessRuleException("synthetic failure")).when(goalService).addProgress(user,goalId,new BigDecimal("100.00"));
        assertThatThrownBy(()->execute(action,proposal(payload,goalId),"sim")).isInstanceOf(BusinessRuleException.class);
        assertThat(receipts.get(action.action_id()).getReceiptJson()).isNull();
        verifyNoInteractions(transactions);
    }
    static Stream<Arguments> confirmations() throws Exception {
        try (var input = GoalContributionConfirmationTest.class.getResourceAsStream("/goal-contribution-confirmations.json")) {
            var cases = new ObjectMapper().readTree(input);
            return java.util.stream.StreamSupport.stream(cases.spliterator(), false)
                .map(item -> Arguments.of(item.get("text").asText(), item.get("accepted").asBoolean()));
        }
    }
    @ParameterizedTest @MethodSource("confirmations")
    void sharedExplicitConfirmationPolicy(String text, boolean accepted) throws Exception {
        var payload = data("100.00");
        var result = execute(action(payload), proposal(payload, goalId), text);
        if (accepted) {
            assertThat(result.confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
            verify(goalService).addProgress(user, goalId, new BigDecimal("100.00"));
        } else rejected(result);
    }
    @Test void oldGoalPayloadCannotUseTheRevisedProposal() throws Exception {
        var oldPayload = data("100.00");
        var revised = new ParsedFinancialData(new BigDecimal("100.00"), "synthetic", null, null, null,
            "Carro", Operation.GOAL_CONTRIBUTION, TemporalStatus.REALIZED, null);
        rejected(execute(action(oldPayload), proposal(revised, UUID.randomUUID()), "sim"));
        verifyNoInteractions(goals);
    }
}
