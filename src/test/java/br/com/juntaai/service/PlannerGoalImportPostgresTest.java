package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.goal.GoalResponse;
import br.com.juntaai.dto.planner.*;
import br.com.juntaai.entity.Role;
import br.com.juntaai.exception.*;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="I3_DISPOSABLE_DB",matches="true")
class PlannerGoalImportPostgresTest extends AbstractIntegrationTest {
    @Autowired PlannerSimulationService simulations;
    @Autowired GoalCommandService commands;
    @Autowired GoalService goals;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @MockBean AiClient ai;
    UUID user,simulation;
    @BeforeEach void seed() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()){assertThat(c.getMetaData().getURL()).contains("junta_i3_disposable_");}
        user=UUID.randomUUID();jdbc.update("insert into users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        simulation=simulations.create(user,new SimulationRequest("Viagem",new BigDecimal("500.25"),10,"no_history",new BigDecimal("50.03"),10,null)).id();
    }
    GoalImportRequest body(){return new GoalImportRequest(null,false);}
    String token(UUID owner){return jwt.generateAccessToken(owner,owner+"@test.invalid",Role.USER);}
    int count(String table){return jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Integer.class,user);}
    @Test void authenticatedImportStartsAtZeroAndPreservesSimulationAndListsGoal() throws Exception {
        var before=simulations.listForUser(user).getFirst(); UUID key=UUID.randomUUID();
        String result=mockMvc.perform(post("/planner/simulations/"+simulation+"/goal")
                .header("Authorization","Bearer "+token(user)).header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"deadline\":null,\"confirmReimport\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Viagem"))
                .andExpect(jsonPath("$.targetAmount").value(500.25)).andExpect(jsonPath("$.currentAmount").value(0))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS")).andReturn().getResponse().getContentAsString();
        var goal=objectMapper.readValue(result,GoalResponse.class);
        assertThat(goals.listForUser(user)).extracting(GoalResponse::id).containsExactly(goal.id());
        assertThat(simulations.listForUser(user)).containsExactly(before);
        assertThat(count("transactions")).isZero(); assertThat(count("planner_goal_imports")).isEqualTo(1);
    }
    @Test void retriesAndNewKeysDoNotCreateDuplicateGoals(){
        UUID key=UUID.randomUUID();var first=commands.importSimulation(user,key,simulation,body());
        assertThat(commands.importSimulation(user,key,simulation,body())).isEqualTo(first);
        assertThatThrownBy(()->commands.importSimulation(user,key,simulation,new GoalImportRequest(LocalDate.now().plusDays(5),false))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->commands.importSimulation(user,UUID.randomUUID(),simulation,body())).isInstanceOf(ConflictException.class).hasMessage("simulation_already_imported");
        assertThat(count("goals")).isEqualTo(1);
    }
    @Test void foreignMissingAndInvalidHeaderRequestsCannotImport() throws Exception {
        UUID other=UUID.randomUUID();jdbc.update("insert into users(id,name,email) values(?,?,?)",other,"Other",other+"@test.invalid");
        mockMvc.perform(post("/planner/simulations/"+simulation+"/goal").header("Authorization","Bearer "+token(other))
                .header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/planner/simulations/"+UUID.randomUUID()+"/goal").header("Authorization","Bearer "+token(user))
                .header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/planner/simulations/"+simulation+"/goal").header("Authorization","Bearer "+token(user))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/planner/simulations/"+simulation+"/goal").header("Authorization","Bearer "+token(user))
                .header("Idempotency-Key",UUID.randomUUID(),UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(count("goals")).isZero(); assertThat(count("goal_command_receipts")).isZero();
    }
    @Test void deletionKeepsImportConsentAndReimportRequiresExplicitConfirmation(){
        UUID key=UUID.randomUUID();var original=commands.importSimulation(user,key,simulation,body());
        commands.delete(user,UUID.randomUUID(),original.id());
        assertThat(jdbc.queryForObject("select goal_id from planner_goal_imports where simulation_id=?",UUID.class,simulation)).isNull();
        assertThat(commands.importSimulation(user,key,simulation,body())).isEqualTo(original); // Historical replay cannot recreate.
        assertThat(count("goals")).isZero();
        assertThatThrownBy(()->commands.importSimulation(user,UUID.randomUUID(),simulation,body())).isInstanceOf(ConflictException.class).hasMessage("simulation_reimport_confirmation_required");
        var next=commands.importSimulation(user,UUID.randomUUID(),simulation,new GoalImportRequest(null,true));
        assertThat(next.id()).isNotEqualTo(original.id()); assertThat(next.currentAmount()).isEqualByComparingTo("0");
        assertThat(count("goals")).isEqualTo(1);assertThat(simulations.listForUser(user)).hasSize(1);
    }
    @Test void failedImportRollsBackGoalLinkAndReceipt(){
        UUID key=UUID.randomUUID();
        assertThatThrownBy(()->commands.importSimulation(user,key,simulation,new GoalImportRequest(LocalDate.now().minusDays(1),false))).isInstanceOf(BusinessRuleException.class);
        assertThat(count("goals")).isZero();assertThat(count("planner_goal_imports")).isZero();assertThat(count("goal_command_receipts")).isZero();
        assertThat(commands.importSimulation(user,key,simulation,new GoalImportRequest(LocalDate.now().plusDays(3),false)).deadline()).isEqualTo(LocalDate.now().plusDays(3));
    }
    @Test void concurrentKeysProduceOnlyOneGoal() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        Callable<Boolean> task=()->{start.await();try{commands.importSimulation(user,UUID.randomUUID(),simulation,body());return true;}catch(ConflictException expected){return false;}};
        try{
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            assertThat(java.util.List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(count("goals")).isEqualTo(1);assertThat(count("planner_goal_imports")).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
}
