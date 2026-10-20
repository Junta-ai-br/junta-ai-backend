package br.com.juntaai.service;

import br.com.juntaai.entity.*;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercise existing rejection guards only; persistence and financial services are mocks. */
class CategoryCatalogExecutionGuardTest {
    private final UUID user = UUID.randomUUID();
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final GoalService goals = mock(GoalService.class);
    private final ActionExecutionService service = new ActionExecutionService(mock(ConversationRepository.class),
            mock(MessageRepository.class), mock(ActionExecutionRepository.class), mock(MessageEntryRepository.class),
            categories, mock(GoalRepository.class), transactions, goals, mock(ConversationTurnStore.class),
            new ObjectMapper(), mock(org.springframework.jdbc.core.JdbcTemplate.class),
            mock(jakarta.persistence.EntityManager.class), mock(jakarta.validation.Validator.class));

    private ActionRequest action() {
        return new ActionRequest("synthetic-action", Operation.EXPENSE,
                new ParsedFinancialData(new BigDecimal("1.00"), "synthetic", "Synthetic category", null,
                        "2026-10-19", null, Operation.EXPENSE, TemporalStatus.REALIZED, null),
                "authoritative_state", null);
    }

    private void rejected(String reason) {
        Object effect = ReflectionTestUtils.invokeMethod(service, "executeEffect", user, action());
        ExecutionConfirmation confirmation = ReflectionTestUtils.invokeMethod(effect, "confirmation");
        assertThat(confirmation.status()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(confirmation.reason()).isEqualTo(reason);
        verifyNoInteractions(transactions, goals);
    }

    @Test void emptyOwnedCatalogCannotExecute() {
        when(categories.findByUserIdOrderByNameAsc(user)).thenReturn(List.of());
        rejected("category_unresolved");
    }

    @Test void foreignCategoryCannotExecuteForAuthenticatedUser() {
        UUID foreignUser = UUID.randomUUID();
        var foreign = Category.builder().name("Synthetic category").type(CategoryType.EXPENSE).build();
        when(categories.findByUserIdOrderByNameAsc(foreignUser)).thenReturn(List.of(foreign));
        when(categories.findByUserIdOrderByNameAsc(user)).thenReturn(List.of());
        rejected("category_unresolved");
        verify(categories, never()).findByUserIdOrderByNameAsc(foreignUser);
    }

    @Test void incompatibleCategoryTypeCannotExecute() {
        var category = Category.builder().name("Synthetic category").type(CategoryType.INCOME).build();
        when(categories.findByUserIdOrderByNameAsc(user)).thenReturn(List.of(category));
        rejected("category_ineligible");
    }

    @Test void queryFailureDoesNotBecomePermissionToExecute() {
        when(categories.findByUserIdOrderByNameAsc(user)).thenThrow(new IllegalStateException("synthetic query failure"));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "executeEffect", user, action()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(transactions, goals);
    }
}
