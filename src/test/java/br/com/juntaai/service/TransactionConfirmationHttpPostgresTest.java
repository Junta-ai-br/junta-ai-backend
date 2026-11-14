package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.integration.ai.model.QuickReplySelection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real AI HTTP on loopback, real disposable PostgreSQL, synthetic data only. */
@EnabledIfEnvironmentVariable(named="PRECONFIRMATION_REAL_AI", matches="true")
@TestPropertySource(properties="ai.service.base-url=http://127.0.0.1:58423")
class TransactionConfirmationHttpPostgresTest extends AbstractIntegrationTest {
    @Autowired ConversationService chat;
    @Autowired JdbcTemplate jdbc;
    UUID user, conversation;
    @BeforeEach void seed() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        user=UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        conversation=chat.createConversation(user,"Synthetic HTTP confirmation").id();
    }
    int effects(){return jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user);}
    @Test void collectionPreviewRevisionBoundConfirmationCommitReceiptAndReplay() {
        var incomplete=chat.sendMessage(user,conversation,new ChatMessageRequest("gastei no mercado hoje"),UUID.randomUUID());
        assertThat(incomplete.actionExecuted()).isFalse();assertThat(effects()).isZero();
        var preview=chat.sendMessage(user,conversation,new ChatMessageRequest("45 reais"),UUID.randomUUID());
        assertThat(preview.actionExecuted()).isFalse(); assertThat(preview.assistantMessage().quickReplies()).hasSize(2);
        assertThat(preview.assistantMessage().content()).contains("45,00","Data:");
        QuickReplySelection old=preview.assistantMessage().quickReplies().getFirst();
        var revised=chat.sendMessage(user,conversation,new ChatMessageRequest("30 reais"),UUID.randomUUID());
        assertThat(revised.actionExecuted()).isFalse();assertThat(effects()).isZero();
        assertThat(revised.assistantMessage().quickReplies().getFirst().pending_id()).isNotEqualTo(old.pending_id());
        assertThatThrownBy(()->chat.sendMessage(user,conversation,new ChatMessageRequest("x",old),UUID.randomUUID())).isInstanceOf(br.com.juntaai.exception.MessageEntryException.class);
        var input=new ChatMessageRequest("Confirmar",revised.assistantMessage().quickReplies().getFirst());
        UUID key=UUID.randomUUID();var receipt=chat.sendMessage(user,conversation,input,key);
        assertThat(receipt.actionExecuted()).isTrue();assertThat(effects()).isEqualTo(1);
        assertThat(receipt.assistantMessage().content()).contains("Registrei");
        assertThat(jdbc.queryForObject("select amount from transactions where user_id=?",BigDecimal.class,user)).isEqualByComparingTo("30.00");
        assertThat(chat.sendMessage(user,conversation,input,key)).isEqualTo(receipt);assertThat(effects()).isEqualTo(1);
    }

    @Test void realInformationAndClarificationKeepOriginalPreviewAndDeadline() {
        var preview=chat.sendMessage(user,conversation,new ChatMessageRequest("gastei 45 no mercado hoje"),UUID.randomUUID());
        var confirm=preview.assistantMessage().quickReplies().getFirst();
        String snapshot=jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation);
        var timestamp=preview.assistantMessage().createdAt();
        for(String question:java.util.List.of("politica de privacidade","qual meu saldo?","nao entendi","politica de privacidade")) {
            var response=chat.sendMessage(user,conversation,new ChatMessageRequest(question),UUID.randomUUID());
            assertThat(response.actionExecuted()).isFalse();assertThat(response.assistantMessage().quickReplies()).isEmpty();
            assertThat(jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation)).isEqualTo(snapshot);
        }
        var original=chat.listMessages(user,conversation).stream().filter(m->m.id().equals(preview.assistantMessage().id())).findFirst().orElseThrow();
        assertThat(original.createdAt()).isEqualTo(timestamp);assertThat(original.quickReplies()).contains(confirm);
        var receipt=chat.sendMessage(user,conversation,new ChatMessageRequest("Confirmar",confirm),UUID.randomUUID());
        assertThat(receipt.actionExecuted()).isTrue();assertThat(effects()).isEqualTo(1);
        assertThat(receipt.assistantMessage().quickReplies()).isEmpty();
    }
    @Test void realInformationCannotRefreshExpiredConsentAndNewPreviewRequiresNewSelection() {
        var preview=chat.sendMessage(user,conversation,new ChatMessageRequest("gastei 45 no mercado hoje"),UUID.randomUUID());
        var old=preview.assistantMessage().quickReplies().getFirst();
        jdbc.update("update messages set created_at=clock_timestamp()-interval '11 minutes' where id=?",preview.assistantMessage().id());
        var info=chat.sendMessage(user,conversation,new ChatMessageRequest("politica de privacidade"),UUID.randomUUID());
        assertThat(info.assistantMessage().quickReplies()).isEmpty();
        assertThatThrownBy(()->chat.sendMessage(user,conversation,new ChatMessageRequest("Confirmar",old),UUID.randomUUID())).isInstanceOf(br.com.juntaai.exception.MessageEntryException.class);
        var fresh=chat.sendMessage(user,conversation,new ChatMessageRequest("sim"),UUID.randomUUID());
        var updated=fresh.assistantMessage().quickReplies().getFirst();
        assertThat(updated.pending_id()).isNotEqualTo(old.pending_id());
        assertThat(fresh.assistantMessage().content()).contains("45,00","Data:");
        assertThatThrownBy(()->chat.sendMessage(user,conversation,new ChatMessageRequest("Confirmar",old),UUID.randomUUID())).isInstanceOf(br.com.juntaai.exception.MessageEntryException.class);
        assertThat(chat.sendMessage(user,conversation,new ChatMessageRequest("Confirmar",updated),UUID.randomUUID()).actionExecuted()).isTrue();
        assertThat(effects()).isEqualTo(1);
    }

    @Test void incomeFreeTextNeverExecutesAndBoundCancelClearsProposal() {
        var preview=chat.sendMessage(user,conversation,new ChatMessageRequest("recebi 100 de salario hoje"),UUID.randomUUID());
        assertThat(preview.assistantMessage().quickReplies()).hasSize(2);assertThat(effects()).isZero();
        var next=chat.sendMessage(user,conversation,new ChatMessageRequest("sim"),UUID.randomUUID());
        assertThat(next.actionExecuted()).isFalse();assertThat(effects()).isZero();
        var cancel=next.assistantMessage().quickReplies().stream().filter(o->o.option_id().equals("cancel")).findFirst().orElseThrow();
        assertThat(chat.sendMessage(user,conversation,new ChatMessageRequest("Cancelar",cancel),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(effects()).isZero();
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation)).isNull();
    }
}
