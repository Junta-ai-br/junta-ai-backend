package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="I3_DISPOSABLE_DB",matches="true")
class GoalWithdrawalPostgresTest extends AbstractIntegrationTest {
    @Autowired ActionExecutionService actions;
    @Autowired JdbcTemplate jdbc;
    @MockBean AiClient ai;
    UUID user,goal;
    @BeforeEach void seed() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()){assertThat(c.getMetaData().getURL()).contains("junta_i3_disposable_");}
        user=UUID.randomUUID(); goal=UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'viagem',200,100)",goal,user);
    }
    record Turn(UUID conversation,UUID message,ActionRequest action){}
    Turn turn(String name,String amount){
        UUID c=UUID.randomUUID(),m=UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id) values(?,?)",c,user);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','synthetic')",m,c);
        return new Turn(c,m,new ActionRequest(UUID.randomUUID().toString(),Operation.GOAL_WITHDRAWAL,
                new ParsedFinancialData(new BigDecimal(amount),"synthetic",null,null,null,name,Operation.GOAL_WITHDRAWAL,TemporalStatus.REALIZED,null),"authoritative_state",Instant.now().plusSeconds(600)));
    }
    ActionExecutionService.Receipt execute(Turn t){return actions.execute(user,t.conversation(),t.message(),"synthetic",t.action(),null);}
    BigDecimal current(){return jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal);}
    @Test void withdrawalAndReceiptAreAtomicAndReplayDoesNotSubtractTwice(){
        var t=turn("viagem","50.25"); var first=execute(t);
        assertThat(first.confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        assertThat(first.confirmation().executed_operation()).isEqualTo(Operation.GOAL_WITHDRAWAL);
        assertThat(execute(t).confirmation()).isEqualTo(first.confirmation());
        assertThat(current()).isEqualByComparingTo("49.75");
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void excessMissingAndHomonymousGoalsAreRejectedWithoutEffects(){
        var excess=turn("viagem","100.01");
        assertThat(execute(excess).confirmation().reason()).isEqualTo("goal_insufficient_progress");
        assertThat(execute(excess).confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(execute(turn("inexistente","1")).confirmation().reason()).isEqualTo("goal_unresolved");
        UUID other=UUID.randomUUID();jdbc.update("insert into users(id,name,email) values(?,?,?)",other,"Other",other+"@test.invalid");
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'foreign',100,100)",UUID.randomUUID(),other);
        assertThat(execute(turn("foreign","1")).confirmation().reason()).isEqualTo("goal_unresolved");
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'viagem',200,100)",UUID.randomUUID(),user);
        assertThat(execute(turn("viagem","1")).confirmation().reason()).isEqualTo("goal_unresolved");
        assertThat(current()).isEqualByComparingTo("100");
    }
    @Test void withdrawalReopensCompletedGoal(){
        jdbc.update("update goals set target_amount=100,status='COMPLETED' where id=?",goal);
        assertThat(execute(turn("viagem","1")).confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        assertThat(current()).isEqualByComparingTo("99");
        assertThat(jdbc.queryForObject("select status from goals where id=?",String.class,goal)).isEqualTo("IN_PROGRESS");
    }
    @Test void twoConcurrentWithdrawalsCannotOverdrawProgress() throws Exception {
        var a=turn("viagem","60"); var b=turn("viagem","60");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->execute(a));var second=pool.submit(()->execute(b));
            var results=java.util.List.of(first.get(20,TimeUnit.SECONDS).confirmation().status(),second.get(20,TimeUnit.SECONDS).confirmation().status());
            assertThat(results).containsExactlyInAnyOrder(ExecutionStatus.SUCCESSFUL,ExecutionStatus.REJECTED);
            assertThat(current()).isEqualByComparingTo("40");
        }finally{pool.shutdownNow();}
    }
}
