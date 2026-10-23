package br.com.juntaai.service;

import br.com.juntaai.entity.Category;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.entity.User;
import br.com.juntaai.repository.CategoryRepository;
import br.com.juntaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class InitialCategoryProvisionerTest {
    @Mock UserRepository users;
    @Mock CategoryRepository categories;
    InitialCategoryProvisioner provisioner;
    User user;

    @BeforeEach void setUp() {
        user = User.builder().build(); user.setId(UUID.randomUUID());
        when(users.findLockedById(user.getId())).thenReturn(Optional.of(user));
        provisioner = new InitialCategoryProvisioner(users, categories);
    }

    @Test void emptyCatalogIsPersistedOnlyForTheLockedOwner() {
        when(categories.findByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of());
        assertThat(provisioner.provision(user.getId()).created()).isEqualTo(14);
        var saved = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        var order = inOrder(users, categories);
        order.verify(users).findLockedById(user.getId());
        order.verify(categories).findByUserIdOrderByNameAsc(user.getId());
        order.verify(categories).saveAllAndFlush(saved.capture());
        var entries = new java.util.ArrayList<Category>();
        saved.getValue().forEach(value -> entries.add((Category) value));
        assertThat(entries).hasSize(14).allSatisfy(category -> assertThat(category.getUser()).isSameAs(user));
        assertThat(entries).extracting(Category::getName).doesNotHaveDuplicates()
                .doesNotContain("Pix", "Empréstimos", "Investimentos");
    }

    @Test void existingCaseInsensitiveHomonymIsNeverOverwritten() {
        var existing = Category.builder().user(user).name("transporte").type(CategoryType.INCOME).build();
        UUID identity = UUID.randomUUID(); existing.setId(identity);
        when(categories.findByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of(existing));
        var result = provisioner.provision(user.getId());
        assertThat(result.created()).isEqualTo(13);
        assertThat(result.conflictingNames()).containsExactly("Transporte");
        assertThat(existing.getId()).isEqualTo(identity);
        assertThat(existing.getName()).isEqualTo("transporte");
        assertThat(existing.getType()).isEqualTo(CategoryType.INCOME);
    }

    @Test void completeCatalogProducesNoWritesOnRetry() {
        when(categories.findByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of());
        var saved = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        provisioner.provision(user.getId());
        verify(categories).saveAllAndFlush(saved.capture());
        var entries = new java.util.ArrayList<Category>();
        saved.getValue().forEach(value -> entries.add((Category) value));
        clearInvocations(categories);
        assertThat(user.isInitialCategoriesInitialized()).isTrue();
        assertThat(provisioner.provision(user.getId()).created()).isZero();
        verify(categories, never()).findByUserIdOrderByNameAsc(any());
        verify(categories, never()).saveAllAndFlush(any());
    }

    @Test void writeFailurePropagatesInsteadOfReportingAnInitializedCatalog() {
        when(categories.findByUserIdOrderByNameAsc(user.getId())).thenReturn(List.of());
        var failure = new IllegalStateException("synthetic_write_failure");
        when(categories.saveAllAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> provisioner.provision(user.getId())).isSameAs(failure);
        assertThat(user.isInitialCategoriesInitialized()).isFalse();
    }
}
