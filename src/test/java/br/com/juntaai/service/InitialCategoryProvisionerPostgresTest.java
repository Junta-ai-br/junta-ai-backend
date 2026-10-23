package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.auth.RegisterCodeRequest;
import br.com.juntaai.dto.auth.RegisterVerifyRequest;
import br.com.juntaai.dto.category.CategoryRequest;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.exception.ConflictException;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real persistence and transaction locks, exclusively on an explicitly disposable database. */
@EnabledIfEnvironmentVariable(named = "P0_DISPOSABLE_DB", matches = "true")
@TestPropertySource(properties = "google.oauth.client-id=synthetic-client")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class InitialCategoryProvisionerPostgresTest extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired CategoryService categories;
    @Autowired MessageEntryStore entries;
    @Autowired ConversationTurnStore turns;
    @SpyBean InitialCategoryProvisioner provisioner;
    @MockBean(name = "googleRestClient", answer = Answers.RETURNS_DEEP_STUBS) RestClient google;

    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("Salário", "INCOME"), Map.entry("Reembolso", "INCOME"),
            Map.entry("Renda Extra", "INCOME"), Map.entry("Bônus e Premiações", "INCOME"),
            Map.entry("Presentes Recebidos", "INCOME"), Map.entry("Alimentação", "EXPENSE"),
            Map.entry("Moradia", "EXPENSE"), Map.entry("Transporte", "EXPENSE"),
            Map.entry("Saúde", "EXPENSE"), Map.entry("Educação", "EXPENSE"),
            Map.entry("Lazer", "EXPENSE"), Map.entry("Streaming", "EXPENSE"),
            Map.entry("Apostas", "EXPENSE"), Map.entry("Outros", "BOTH"));

    @org.springframework.test.context.DynamicPropertySource
    static void requireDisposableConnection(org.springframework.test.context.DynamicPropertyRegistry registry) {
        String url = System.getenv("DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/junta_p0_disposable_[a-z0-9_]+")) {
            throw new IllegalStateException("A loopback disposable database is required before Spring starts");
        }
    }

    @BeforeEach void requireDisposableDatabase() {
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .startsWith("junta_p0_disposable_");
    }

    private UUID existingUser() {
        UUID user = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?, 'Synthetic', ?)", user, user + "@test.invalid");
        return user;
    }
    private int count(UUID user) {
        return jdbc.queryForObject("select count(*) from categories where user_id=?", Integer.class, user);
    }
    private void assertCatalog(UUID user) {
        var actual = jdbc.query("select name,type from categories where user_id=?", (rs, row) ->
                Map.entry(rs.getString(1), rs.getString(2)), user);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED.entrySet());
    }

    @Test void emailRegistrationPersistsExactApprovedCatalog() throws Exception {
        String email = uniqueEmail("p0-registration");
        registerAndGetAccessToken("Synthetic", email, "11999998888");
        assertCatalog(users.findByEmail(email).orElseThrow().getId());
    }

    @Test void googleRegistrationPersistsCatalogAndRepeatedLoginDoesNotDuplicate() {
        String email = UUID.randomUUID() + "@test.invalid";
        when(google.get().uri("/tokeninfo?id_token={token}", "synthetic-token").retrieve().body(Map.class))
                .thenReturn(Map.of("aud", "synthetic-client", "email_verified", "true",
                        "sub", UUID.randomUUID().toString(), "email", email, "name", "Synthetic"));
        auth.loginWithGoogle("synthetic-token");
        var user = users.findByEmail(email).orElseThrow().getId();
        assertCatalog(user);
        auth.loginWithGoogle("synthetic-token");
        assertCatalog(user);
    }

    @Test void provisioningIsIdempotentAndUserScoped() {
        UUID user = existingUser(), other = existingUser();
        assertThat(provisioner.provision(user).created()).isEqualTo(14);
        assertThat(provisioner.provision(user).created()).isZero();
        assertCatalog(user);
        assertThat(count(other)).isZero();
        provisioner.provision(other);
        assertCatalog(other);
        assertThat(jdbc.queryForObject("select count(distinct id) from categories where user_id in (?,?)",
                Integer.class, user, other)).isEqualTo(28);
    }

    @Test void preservesCustomNamesTypesIdentitiesAndExistingTransactions() {
        UUID user = existingUser(), custom = UUID.randomUUID(), transaction = UUID.randomUUID();
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'transporte','INCOME')", custom, user);
        jdbc.update("insert into transactions(id,user_id,category_id,type,amount,transaction_date) values(?,?,?,'INCOME',20,current_date)",
                transaction, user, custom);
        categories.create(user, new CategoryRequest("Categoria pessoal", CategoryType.EXPENSE));
        var result = provisioner.provision(user);
        assertThat(result.created()).isEqualTo(13);
        assertThat(result.conflictingNames()).containsExactly("Transporte");
        assertThat(count(user)).isEqualTo(15);
        assertThat(jdbc.queryForObject("select type from categories where id=?", String.class, custom)).isEqualTo("INCOME");
        assertThat(jdbc.queryForObject("select category_id from transactions where id=?", UUID.class, transaction)).isEqualTo(custom);
        assertThat(provisioner.provision(user).created()).isZero();
        assertThat(count(user)).isEqualTo(15);
    }

    @Test void authenticatedMessageRepairsExistingAccountBeforeSnapshotAndReplayDoesNotWriteAgain() {
        UUID user = existingUser(), conversation = UUID.randomUUID(), key = UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        var first = entries.admit(user, conversation, key, "synthetic");
        assertThat(first.fresh()).isTrue();
        assertCatalog(user);
        assertThat(first.turn().snapshot().current_state().get("category_catalog")).asList().hasSize(14);
        var replay = entries.admit(user, conversation, key, "synthetic");
        assertThat(replay.fresh()).isFalse();
        assertThat(replay.turn().snapshot()).isNull();
        verify(provisioner, times(1)).provision(user);
    }

    @Test void foreignConversationCannotTriggerProvisioning() {
        UUID user = existingUser(), foreign = existingUser(), conversation = UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        assertThatThrownBy(() -> entries.admit(foreign, conversation, UUID.randomUUID(), "synthetic"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count(user)).isZero();
        assertThat(count(foreign)).isZero();
    }

    @Test void snapshotPreservesWireContractAndFinancialEligibility() {
        UUID user = existingUser();
        provisioner.provision(user);
        var wire = objectMapper.valueToTree(turns.buildFinancialSnapshot(user));
        var catalog = wire.path("current_state").path("category_catalog");
        assertThat(catalog.size()).isEqualTo(14);
        for (var category : catalog) {
            assertThat(category.size()).isEqualTo(3);
            assertThat(UUID.fromString(category.path("id").asText())).isNotNull();
            assertThat(category.path("type").asText()).isEqualTo(EXPECTED.get(category.path("name").asText()));
        }
        assertThat(EXPECTED).containsEntry("Transporte", "EXPENSE").containsEntry("Alimentação", "EXPENSE")
                .containsEntry("Salário", "INCOME").containsEntry("Outros", "BOTH");
    }

    @Test void concurrentInitializationsDoNotDuplicateCategories() throws Exception {
        UUID user = existingUser();
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test_timeout");
                return provisioner.provision(user).created();
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            int created = 0;
            for (var task : tasks) created += task.get(10, TimeUnit.SECONDS);
            assertThat(created).isEqualTo(14);
        }
        assertCatalog(user);
    }

    @Test void concurrentCrudAndProvisioningRespectCaseInsensitiveIdentity() throws Exception {
        UUID user = existingUser();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var provision = pool.submit(() -> { start.await(); return provisioner.provision(user); });
            var custom = pool.submit(() -> {
                start.await();
                try { categories.create(user, new CategoryRequest("transporte", CategoryType.INCOME)); }
                catch (ConflictException expected) { /* Provisioning won the user's lock. */ }
                return true;
            });
            start.countDown(); provision.get(10, TimeUnit.SECONDS); custom.get(10, TimeUnit.SECONDS);
        }
        assertThat(count(user)).isEqualTo(14);
        assertThat(jdbc.queryForObject("select count(*) from categories where user_id=? and lower(name)='transporte'",
                Integer.class, user)).isEqualTo(1);
    }

    @Test void registrationRollsBackUserAndCategoriesWhenProvisioningFails() {
        String email = UUID.randomUUID() + "@test.invalid";
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        doNothing().when(emailSender).sendAccessCode(any(), code.capture());
        auth.requestRegistrationCode(new RegisterCodeRequest(email));
        doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("synthetic_provisioning_failure"); })
                .when(provisioner).provision(any());
        assertThatThrownBy(() -> auth.completeRegistration(new RegisterVerifyRequest(
                "Synthetic", email, "11999998888", code.getValue(), null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test void failedAuthenticatedAdmissionRollsBackCategoriesAndMessage() {
        UUID user = existingUser(), conversation = UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("synthetic_provisioning_failure"); })
                .when(provisioner).provision(user);
        assertThatThrownBy(() -> entries.admit(user, conversation, UUID.randomUUID(), "synthetic"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(user)).isZero();
        assertThat(jdbc.queryForObject("select initial_categories_initialized from users where id=?", Boolean.class, user)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from conversation_message_entries where user_id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from messages where conversation_id=?", Integer.class, conversation)).isZero();
    }
    @Test void simultaneousMessagesInDifferentConversationsShareOneCatalog() throws Exception {
        UUID user = existingUser(), first = UUID.randomUUID(), second = UUID.randomUUID();
        for (var conversation : List.of(first, second)) {
            jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        }
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(first, second).stream().map(conversation -> pool.submit(() -> {
                start.await(); return entries.admit(user, conversation, UUID.randomUUID(), "synthetic");
            })).toList();
            start.countDown();
            for (var task : tasks) assertThat(task.get(10, TimeUnit.SECONDS).fresh()).isTrue();
        }
        assertCatalog(user);
    }

    @Test void googleRegistrationAlsoRollsBackWhenProvisioningFails() {
        String email = UUID.randomUUID() + "@test.invalid";
        when(google.get().uri("/tokeninfo?id_token={token}", "synthetic-token").retrieve().body(Map.class))
                .thenReturn(Map.of("aud", "synthetic-client", "email_verified", "true",
                        "sub", UUID.randomUUID().toString(), "email", email, "name", "Synthetic"));
        doAnswer(inv -> { inv.callRealMethod(); throw new IllegalStateException("synthetic_provisioning_failure"); })
                .when(provisioner).provision(any());
        assertThatThrownBy(() -> auth.loginWithGoogle("synthetic-token")).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByEmail(email)).isEmpty();
    }

    @Test void renamingCustomCategoryCannotIntroduceCaseInsensitiveDefaultDuplicate() {
        UUID user = existingUser();
        provisioner.provision(user);
        var custom = categories.create(user, new CategoryRequest("Categoria pessoal", CategoryType.INCOME));
        assertThatThrownBy(() -> categories.update(user, custom.id(),
                new CategoryRequest("transporte", CategoryType.INCOME))).isInstanceOf(ConflictException.class);
        assertThat(count(user)).isEqualTo(15);
        assertThat(jdbc.queryForObject("select name from categories where id=?", String.class, custom.id()))
                .isEqualTo("Categoria pessoal");
    }

    @Test void deletingUnusedDefaultSurvivesTheNextAuthenticatedMessage() {
        UUID user = existingUser(), conversation = UUID.randomUUID();
        provisioner.provision(user);
        UUID category = jdbc.queryForObject("select id from categories where user_id=? and name='Apostas'", UUID.class, user);
        categories.delete(user, category);
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        var admission = entries.admit(user, conversation, UUID.randomUUID(), "synthetic");
        assertThat(count(user)).isEqualTo(13);
        assertThat(jdbc.queryForObject("select initial_categories_initialized from users where id=?", Boolean.class, user)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from categories where id=?", Integer.class, category)).isZero();
        var catalog = objectMapper.valueToTree(admission.turn().snapshot()).path("current_state").path("category_catalog");
        assertThat(catalog.size()).isEqualTo(13);
        for (var entry : catalog) assertThat(entry.path("name").asText()).isNotEqualTo("Apostas");
    }

    @Test void deletingAllCategoriesDoesNotMakeAnInitializedAccountNewAgain() {
        UUID user = existingUser(), conversation = UUID.randomUUID();
        provisioner.provision(user);
        var ids = jdbc.queryForList("select id from categories where user_id=?", UUID.class, user);
        ids.forEach(id -> categories.delete(user, id));
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        var admission = entries.admit(user, conversation, UUID.randomUUID(), "synthetic");
        assertThat(count(user)).isZero();
        assertThat(admission.turn().snapshot().current_state().get("category_catalog")).asList().isEmpty();
        assertThat(jdbc.queryForObject("select initial_categories_initialized from users where id=?", Boolean.class, user)).isTrue();
    }

}
