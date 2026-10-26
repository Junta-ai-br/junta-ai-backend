package br.com.juntaai.service;

import br.com.juntaai.dto.dashboard.DashboardResponse;
import br.com.juntaai.entity.*;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConversationCategoryCatalogTest {
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final CategoryService categoryService = new CategoryService(categories, mock(UserRepository.class), transactions);
    private final DashboardService dashboard = mock(DashboardService.class);
    private final GoalRepository goals = mock(GoalRepository.class);
    private final UUID user = UUID.randomUUID();
    private final ConversationTurnStore turns = new ConversationTurnStore(mock(ConversationRepository.class),
            mock(MessageRepository.class), goals, transactions, mock(ActionExecutionRepository.class),
            mock(MessageEntryRepository.class), dashboard, categoryService, new ObjectMapper(),
            mock(jakarta.persistence.EntityManager.class));

    @BeforeEach void setUp() {
        when(dashboard.summarize(any(), any(), any())).thenReturn(new DashboardResponse(null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null));
        when(transactions.findByUserIdAndTransactionDateBetween(any(), any(), any(), any())).thenReturn(Page.empty());
        when(transactions.findByUserId(any(), any())).thenReturn(Page.empty());
        when(goals.findByUserIdOrderByDeadlineAsc(any())).thenReturn(List.of());
    }

    private Category category(UUID ownerId, String name, CategoryType type) {
        var owner = User.builder().build(); owner.setId(ownerId);
        var category = Category.builder().user(owner).name(name).type(type).build();
        category.setId(UUID.randomUUID());
        return category;
    }

    @Test void includesOnlyIdentityNameAndRealTypeInAuthorizedSnapshot() throws Exception {
        var category = category(user, "Synthetic food", CategoryType.EXPENSE);
        when(categories.findByUserIdOrderByNameAsc(user)).thenReturn(List.of(category));
        var snapshot = turns.buildFinancialSnapshot(user);
        var wire = new ObjectMapper().valueToTree(snapshot);
        var entry = wire.path("current_state").path("category_catalog").get(0);
        assertThat(entry.size()).isEqualTo(3);
        assertThat(entry.path("id").asText()).isEqualTo(category.getId().toString());
        assertThat(entry.path("name").asText()).isEqualTo(category.getName());
        assertThat(entry.path("type").asText()).isEqualTo("EXPENSE");
        assertThat(entry.has("eligible")).isFalse();
        assertThat(entry.has("createdAt")).isFalse();
        verify(categories).findByUserIdOrderByNameAsc(user);
        verifyNoMoreInteractions(categories);
    }

    @Test void emptyCatalogIsExplicitEmptyListRatherThanStaticDefaults() {
        when(categories.findByUserIdOrderByNameAsc(user)).thenReturn(List.of());
        assertThat((List<?>) turns.buildFinancialSnapshot(user).current_state().get("category_catalog")).isEmpty();
    }

    @Test void sameNamedCategoriesRemainIsolatedBetweenUsersAndCalls() {
        UUID other = UUID.randomUUID();
        var own = category(user, "Synthetic food", CategoryType.EXPENSE);
        var foreign = category(other, "Synthetic food", CategoryType.INCOME);
        when(categories.findByUserIdOrderByNameAsc(user)).thenReturn(List.of(own));
        when(categories.findByUserIdOrderByNameAsc(other)).thenReturn(List.of(foreign));
        var first = (List<?>) turns.buildFinancialSnapshot(user).current_state().get("category_catalog");
        var second = (List<?>) turns.buildFinancialSnapshot(other).current_state().get("category_catalog");
        assertThat(first).isEqualTo(List.of(new CategoryCatalogEntry(own.getId(), own.getName(), CategoryType.EXPENSE)));
        assertThat(second).isEqualTo(List.of(new CategoryCatalogEntry(foreign.getId(), foreign.getName(), CategoryType.INCOME)));
        assertThat(first.contains(second.getFirst())).isFalse();
    }

    @Test void queryFailureAbortsSnapshotInsteadOfSupplyingUnverifiedFallback() {
        var failure = new IllegalStateException("synthetic lookup failure");
        when(categories.findByUserIdOrderByNameAsc(user)).thenThrow(failure);
        assertThatThrownBy(() -> turns.buildFinancialSnapshot(user)).isSameAs(failure);
    }

    @Test void bothTypeIsPreservedWithoutInventingEligibilityState() {
        var category = category(user, "Synthetic shared", CategoryType.BOTH);
        when(categories.findByUserIdOrderByNameAsc(user)).thenReturn(List.of(category));
        assertThat((List<?>) turns.buildFinancialSnapshot(user).current_state().get("category_catalog"))
                .isEqualTo(List.of(new CategoryCatalogEntry(category.getId(), category.getName(), CategoryType.BOTH)));
    }
    @Test void sharesTheExactDashboardIndicatorWithChatInsteadOfScoringRecentTransactions() {
        var first = java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1);
        var last = first.withDayOfMonth(first.lengthOfMonth());
        var health = new br.com.juntaai.dto.dashboard.HealthScoreResponse("mvp-monthly-v1", "CALCULATED", 65,
                "No caminho certo", first, last);
        when(dashboard.summarize(user, first, last)).thenReturn(new DashboardResponse(first, last,
                new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("500"), List.of(), health));
        when(categories.findByUserIdOrderByNameAsc(user)).thenReturn(List.of(category(user, "Personalizada", CategoryType.BOTH)));
        var snapshot = turns.buildFinancialSnapshot(user);
        assertThat(snapshot.indicators().get("healthScore")).isSameAs(health);
        assertThat(snapshot.realized_records()).isEmpty();
        assertThat(snapshot.projected_state()).isEmpty();
        verify(dashboard).summarize(user, first, last);
    }

}
