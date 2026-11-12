package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.dto.goal.GoalRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real local deterministic AI HTTP + committed PostgreSQL effects, no mock AI. */
@EnabledIfEnvironmentVariable(named = "GOALS_REAL_AI", matches = "true")
@TestPropertySource(properties = "ai.service.base-url=http://127.0.0.1:58123")
class GoalRecognitionHttpPostgresTest extends AbstractIntegrationTest {
    @Autowired ConversationService conversations;
    @Autowired GoalCommandService commands;
    @Autowired JdbcTemplate jdbc;
    UUID user, goal, conversation;

    @BeforeEach void seed() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql://127.0.0.1:54837/junta_i3_disposable_");
        }
        user = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", user, "Synthetic", user + "@test.invalid");
        UUID category = UUID.randomUUID();
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Synthetic funding','INCOME')", category, user);
        jdbc.update("insert into transactions(id,user_id,category_id,type,amount,transaction_date) values(?,?,?,'INCOME',20000,current_date)", UUID.randomUUID(), user, category);
        goal = commands.create(user, UUID.randomUUID(), new GoalRequest("viagem", new BigDecimal("1000"), null)).id();
        conversation = conversations.createConversation(user, "Synthetic").id();
    }

    void propose(String value) {
        var first = conversations.sendMessage(user, conversation, new ChatMessageRequest("Guardei " + value + " reais para viagem"), UUID.randomUUID());
        assertThat(first.actionExecuted()).isFalse();
        var source = first.assistantMessage().quickReplies().getFirst();
        assertThat(conversations.sendMessage(user, conversation, new ChatMessageRequest("já registrei", source), UUID.randomUUID()).actionExecuted()).isFalse();
    }

    @Test void firstSixCopiesAreComposedFromRealCommittedReceipts() {
        String[] copies = {
            "Boa! 💜 Mais um passo em direção à sua meta.",
            "Mais um pouquinho guardado para os seus planos. ✨",
            "Olha só, você já guardou dinheiro três vezes nessa meta! 💜 Cada contribuição conta!",
            "Contribuição registrada! Seguimos no seu ritmo. 💜",
            "Eita, já são cinco contribuições! 🎉 Você está dando continuidade aos seus planos. Que legal!",
            "Mais um passo para a sua meta! 💜"
        };
        for (String copy : copies) {
            propose("1");
            var reply = conversations.sendMessage(user, conversation, new ChatMessageRequest("sim"), UUID.randomUUID());
            assertThat(reply.actionExecuted()).isTrue();
            assertThat(reply.assistantMessage().content()).contains("Guardei R$ 1,00").endsWith(copy);
        }
        assertThat(jdbc.queryForObject("select confirmed_contribution_count from goals where id=?", Long.class, goal)).isEqualTo(6);
    }

    @Test void largestMilestoneWinsAndReplayDoesNotCreateAnotherContribution() {
        propose("800");
        UUID key = UUID.randomUUID();
        var first = conversations.sendMessage(user, conversation, new ChatMessageRequest("sim"), key);
        assertThat(first.actionExecuted()).isTrue();
        assertThat(first.assistantMessage().content()).endsWith("UAAAAU! 🎉 Você já alcançou 75% da sua meta! Falta só mais um quarto!");
        var replay = conversations.sendMessage(user, conversation, new ChatMessageRequest("sim"), key);
        assertThat(replay.assistantMessage().id()).isEqualTo(first.assistantMessage().id());
        assertThat(jdbc.queryForObject("select confirmed_contribution_count from goals where id=?", Long.class, goal)).isEqualTo(1);
    }

    @Test void completionCopyRequiresTheCommittedCompletedGoal() {
        propose("1000");
        var reply = conversations.sendMessage(user, conversation, new ChatMessageRequest("sim"), UUID.randomUUID());
        assertThat(reply.actionExecuted()).isTrue();
        assertThat(reply.assistantMessage().content()).endsWith("AEEEEEE! 🎉💜 Você conseguiu! Sua meta foi alcançada! Que conquista maravilhosa!");
        assertThat(jdbc.queryForObject("select status from goals where id=?", String.class, goal)).isEqualTo("COMPLETED");
    }
}
