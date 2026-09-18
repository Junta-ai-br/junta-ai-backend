package br.com.juntaai.service;

import br.com.juntaai.dto.category.CategoryRequest;
import br.com.juntaai.dto.category.CategoryResponse;
import br.com.juntaai.entity.Category;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ConflictException;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.repository.CategoryRepository;
import br.com.juntaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    private CategoryService categoryService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository, userRepository);
    }

    @Test
    void deveCriarCategoriaQuandoNomeAindaNaoExistePraOUsuario() {
        CategoryRequest request = new CategoryRequest("Alimentação", CategoryType.EXPENSE);
        User user = User.builder().build();
        user.setId(userId);

        when(categoryRepository.existsByUserIdAndNameIgnoreCase(userId, "Alimentação")).thenReturn(false);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> {
            Category c = invocation.getArgument(0);
            c.setId(UUID.randomUUID());
            c.setCreatedAt(Instant.now());
            return c;
        });

        CategoryResponse response = categoryService.create(userId, request);

        assertThat(response.name()).isEqualTo("Alimentação");
        assertThat(response.type()).isEqualTo("EXPENSE");
    }

    @Test
    void deveLancarConflitoQuandoNomeDeCategoriaJaExistePraOUsuario() {
        CategoryRequest request = new CategoryRequest("Alimentação", CategoryType.EXPENSE);
        when(categoryRepository.existsByUserIdAndNameIgnoreCase(userId, "Alimentação")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(userId, request))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deveLancarNotFoundQuandoCategoriaNaoPertenceAoUsuario() {
        UUID categoryId = UUID.randomUUID();
        UUID outroUsuarioId = UUID.randomUUID();

        when(categoryRepository.findByIdAndUserId(categoryId, outroUsuarioId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.findOwnedOrThrow(outroUsuarioId, categoryId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
