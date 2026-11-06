package br.com.juntaai.service;
import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.entity.*;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.exception.MessageEntryException;
import br.com.juntaai.integration.ai.AiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="I3_DISPOSABLE_DB",matches="true")
class ConversationChoicePostgresTest extends AbstractIntegrationTest {
    @Autowired MessageEntryStore entries;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean AiClient ai;
    UUID user, conversation, goal, prompt;
    @BeforeEach void seed() {
        assertThat(jdbc.queryForObject("select current_database()",String.class)).startsWith("junta_i3_disposable_");
        user=UUID.randomUUID(); conversation=UUID.randomUUID(); goal=UUID.randomUUID(); prompt=UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'Placa',1000,0)",goal,user);
        jdbc.update("insert into conversations(id,user_id) values(?,?)",conversation,user);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'ASSISTANT','Synthetic')",prompt,conversation);
    }
    PendingOperation pending(List<String> fields,Map<String,String> candidates) {
        var data=new ParsedFinancialData(new BigDecimal("150"),null,null,null,null,"Placa",Operation.GOAL_CONTRIBUTION,TemporalStatus.REALIZED,null);
        return new PendingOperation(PendingState.GOAL,Operation.GOAL_CONTRIBUTION,data,fields,goal.toString(),UUID.randomUUID().toString(),conversation.toString(),"registered",prompt.toString(),candidates);
    }
    QuickReplySelection choose(PendingOperation pending,String option) throws Exception {
        jdbc.update("update conversations set pending_state=? where id=?",mapper.writeValueAsString(pending),conversation);
        return new QuickReplySelection(option,pending.pending_id(),conversation.toString(),"Untrusted display label");
    }
    @Test void ownedGoalChoiceUsesTheBoundIdAndReplaysOnlyTheSameIngress() throws Exception {
        var selection=choose(pending(List.of("goal_reference"),Map.of(goal.toString(),"Placa")),"goal:"+goal);
        UUID key=UUID.randomUUID();
        var first=entries.admit(user,conversation,key,selection.label(),selection);
        var replay=entries.admit(user,conversation,key,selection.label(),selection);
        assertThat(first.turn().userMessage().content()).isEqualTo("Placa");
        assertThat(jdbc.queryForObject("select content from messages where id=?",String.class,first.turn().messageId())).isEqualTo("goal:"+goal);
        assertThat(replay.fresh()).isFalse();
        assertThat(first.turn().messageId()).isEqualTo(replay.turn().messageId());
        assertThat(first.turn().pending().goal_candidates()).containsEntry(goal.toString(),"Placa");
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("0");
    }
    @Test void homonymousChoicesCannotReuseTheSameKeyForDifferentIds() throws Exception {
        UUID other=UUID.randomUUID(), key=UUID.randomUUID();
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'Placa',1000,0)",other,user);
        var state=pending(List.of("goal_reference"),Map.of(goal.toString(),"Placa",other.toString(),"Placa"));
        var first=choose(state,"goal:"+goal);
        entries.admit(user,conversation,key,first.label(),first);
        var changed=new QuickReplySelection("goal:"+other,state.pending_id(),conversation.toString());
        assertThatThrownBy(()->entries.admit(user,conversation,key,changed.label(),changed))
                .isInstanceOf(MessageEntryException.class).extracting("code").isEqualTo("idempotency_key_conflict");
    }
    @Test void aForeignGoalIsRejectedEvenIfItAppearsInCandidateMetadata() throws Exception {
        UUID other=UUID.randomUUID(),foreign=UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)",other,"Other",other+"@test.invalid");
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'Placa',1000,0)",foreign,other);
        var selection=choose(pending(List.of("goal_reference"),Map.of(foreign.toString(),"Placa")),"goal:"+foreign);
        assertThatThrownBy(()->entries.admit(user,conversation,UUID.randomUUID(),selection.label(),selection)).isInstanceOf(MessageEntryException.class);
    }
    @Test void confirmationChoiceCannotBeUsedAsSourceAndOldPromptCannotConfirm() throws Exception {
        var source=pending(List.of("funding_source"),Map.of());
        var wrong=choose(source,"confirm");
        assertThatThrownBy(()->entries.admit(user,conversation,UUID.randomUUID(),wrong.label(),wrong)).isInstanceOf(MessageEntryException.class);
        var confirmation=pending(List.of("confirmation"),Map.of());
        var old=choose(confirmation,"confirm");
        choose(confirmation.withPrompt(UUID.randomUUID().toString()),"confirm");
        assertThatThrownBy(()->entries.admit(user,conversation,UUID.randomUUID(),old.label(),old)).isInstanceOf(MessageEntryException.class);
    }
}
