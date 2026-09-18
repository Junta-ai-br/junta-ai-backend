package br.com.juntaai.service;

import br.com.juntaai.dto.transaction.TransactionRequest;
import br.com.juntaai.entity.Category;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.entity.Transaction;
import br.com.juntaai.entity.TransactionType;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.repository.TransactionRepository;
import br.com.juntaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CategoryService categoryService;

    private TransactionService transactionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, userRepository, categoryService);
    }

    @Test
    void deveCriarTransacaoQuandoCategoriaPertenceAoUsuario() {
        TransactionRequest request = new TransactionRequest(
                categoryId, TransactionType.EXPENSE, "Mercado", new BigDecimal("120.00"), LocalDate.now());

        Category category = Category.builder().name("Alimentação").type(CategoryType.EXPENSE).build();
        category.setId(categoryId);
        User user = User.builder().build();
        user.setId(userId);

        when(categoryService.findOwnedOrThrow(userId, categoryId)).thenReturn(category);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        var response = transactionService.create(userId, request);

        assertThat(response.amount()).isEqualByComparingTo("120.00");
        assertThat(response.categoryName()).isEqualTo("Alimentação");
    }

    @Test
    void naoDeveCriarTransacaoQuandoCategoriaNaoPertenceAoUsuario() {
        TransactionRequest request = new TransactionRequest(
                categoryId, TransactionType.EXPENSE, "Mercado", new BigDecimal("120.00"), LocalDate.now());

        when(categoryService.findOwnedOrThrow(userId, categoryId))
                .thenThrow(new ResourceNotFoundException("Categoria"));

        assertThatThrownBy(() -> transactionService.create(userId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
