package br.com.juntaai.service;

import br.com.juntaai.JuntaAiApplication;
import br.com.juntaai.dto.category.CategoryRequest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.dto.planner.GoalImportRequest;
import br.com.juntaai.dto.transaction.TransactionRequest;
import br.com.juntaai.entity.*;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Test-only second backend JVM. Stdin/stdout carry synthetic IDs, never real user data. */
public final class FinancialWriteProcess {
    static final String FRAME = "LOCK_TEST:";
    public record Input(String operation, UUID user, UUID conversation, UUID category, UUID transaction,
                        UUID goal, UUID simulation, UUID message, UUID key, ActionRequest action,
                        String pending, QuickReplySelection selection) {}

    @TestConfiguration(proxyBeanMethods = false)
    public static class SyntheticAi {
        @Bean @Primary AiClient coordinationAi() {
            AiClient ai = mock(AiClient.class);
            when(ai.process(any())).thenAnswer(call -> reply(call.getArgument(0)));
            return ai;
        }
    }

    static ProcessingResult reply(AIServiceRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new AssertionError("AI called inside a database transaction");
        boolean composition = request.execution_confirmation() != null;
        ActionRequest action = composition ? null : new ActionRequest(UUID.randomUUID().toString(),
                request.pending_operation().operation(), request.pending_operation().known_fields(),
                "authoritative_state", Instant.now().plusSeconds(600));
        return new ProcessingResult(request.request_id(), request.contract_version(), null,
                new Decision(composition ? DecisionType.RESPONSE : DecisionType.ACTION_REQUEST,
                        composition ? "execution_confirmed" : null, List.of()), action, null,
                new ConversationalResponse("Synthetic coordination result", List.of()), null, null);
    }

    public static void main(String[] args) throws Exception {
        String url = System.getenv("DB_URL");
        if (url == null || !url.startsWith("jdbc:postgresql://127.0.0.1:")
                || !url.contains("/junta_i3_disposable_")) throw new IllegalStateException("Disposable database required");
        try (var app = new SpringApplicationBuilder(JuntaAiApplication.class, SyntheticAi.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
                "--spring.profiles.active=local", "--spring.mail.username=", "--spring.mail.password=", "--app.mail.from=",
                "--spring.datasource.hikari.data-source-properties.ApplicationName=financial-lock-worker",
                "--spring.datasource.hikari.connection-init-sql=SET lock_timeout='5s'",
                "--management.health.mail.enabled=false",
                "--jwt.secret=segredo-somente-para-testes-com-mais-de-32-caracteres",
                "--ai.service.token=synthetic-ai-service-token")) {
            ObjectMapper mapper = app.getBean(ObjectMapper.class);
            System.out.println(FRAME + "READY");
            var input = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
            for (String line; (line = input.readLine()) != null;) {
                try {
                    Input p = mapper.readValue(line, Input.class);
                    var turns = app.getBean(ConversationTurnStore.class);
                    var entries = app.getBean(MessageEntryStore.class);
                    var executor = app.getBean(ActionExecutionService.class);
                    var transactions = app.getBean(TransactionService.class);
                    var categories = app.getBean(CategoryService.class);
                    var goals = app.getBean(GoalService.class);
                    switch (p.operation()) {
                        case "ADMIT" -> entries.admit(p.user(), p.conversation(), p.key(), "synthetic");
                        case "BEGIN" -> turns.begin(p.user(), p.conversation(), "synthetic");
                        case "FINISH" -> turns.finish(p.user(), p.conversation(), turns.restore(p.message(), null, false),
                                "synthetic", null, false, null, false, false);
                        case "COMPLETED" -> turns.completed(p.user(), p.conversation(), p.message());
                        case "SELECT" -> entries.select(p.user(), p.conversation(), p.message(), p.action());
                        case "FAIL" -> entries.failInitial(p.user(), p.conversation(), p.message());
                        case "COMPLETE" -> entries.completeResponse(p.user(), p.conversation(),
                                turns.restore(p.message(), null, false), "synthetic", null, false);
                        case "EXECUTE" -> executor.execute(p.user(), p.conversation(), p.message(),
                                "lock-test-request", p.action(), p.pending());
                        case "RECEIPT" -> executor.recordedReceipt(p.user(), p.conversation(), p.action().action_id());
                        case "IMPORT" -> app.getBean(PlannerGoalImportService.class)
                                .importGoal(p.user(), p.simulation(), new GoalImportRequest(null, false));
                        case "TX_CREATE" -> transactions.create(p.user(), transaction(p));
                        case "TX_UPDATE" -> transactions.update(p.user(), p.transaction(), transaction(p));
                        case "TX_DELETE" -> transactions.delete(p.user(), p.transaction());
                        case "TX_CORRECT" -> transactions.correct(p.user(), p.transaction(), Instant.EPOCH,
                                TransactionType.EXPENSE, p.category(), BigDecimal.ONE);
                        case "CAT_CREATE" -> categories.create(p.user(), new CategoryRequest("Worker category", CategoryType.BOTH));
                        case "CAT_UPDATE" -> categories.update(p.user(), p.category(), new CategoryRequest("Changed category", CategoryType.BOTH));
                        case "CAT_DELETE" -> categories.delete(p.user(), p.category());
                        case "GOAL_PROGRESS" -> goals.addProgress(p.user(), p.goal(), BigDecimal.ONE);
                        case "GOAL_UPDATE" -> goals.update(p.user(), p.goal(), new GoalRequest("Changed goal", new BigDecimal("200"), null));
                        case "GOAL_DELETE" -> goals.delete(p.user(), p.goal());
                        case "ACCOUNT_DELETE" -> app.getBean(AccountDeletionService.class).deleteAccount(p.user());
                        case "CONFIRM" -> app.getBean(ConversationService.class).sendMessage(p.user(), p.conversation(),
                                new ChatMessageRequest("Confirmar", p.selection()), p.key());
                        default -> throw new IllegalArgumentException("Unknown synthetic operation");
                    }
                    System.out.println(FRAME + "OK");
                } catch (Exception ex) {
                    // Safe codes/classes only, never request bodies or financial state.
                    String code = ex instanceof br.com.juntaai.exception.MessageEntryException ingress
                            ? ingress.getCode() : ex.getClass().getSimpleName();
                    System.out.println(FRAME + "ERROR:" + code);
                }
            }
        }
    }

    static TransactionRequest transaction(Input p) {
        return new TransactionRequest(p.category(), TransactionType.EXPENSE, "Synthetic worker expense",
                new BigDecimal("7.00"), LocalDate.of(2020, 1, 1));
    }
}
