package br.com.juntaai.service;

import br.com.juntaai.dto.category.CategoryRequest;
import br.com.juntaai.dto.category.CategoryResponse;
import br.com.juntaai.entity.Category;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ConflictException;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.repository.CategoryRepository;
import br.com.juntaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Transactional
    public CategoryResponse create(UUID userId, CategoryRequest request) {
        if (categoryRepository.existsByUserIdAndNameIgnoreCase(userId, request.name())) {
            throw new ConflictException("Você já possui uma categoria com este nome.");
        }

        User user = userRepository.getReferenceById(userId);

        Category category = Category.builder()
                .user(user)
                .name(request.name())
                .type(request.type())
                .build();

        return toResponse(categoryRepository.save(category));
    }

    public List<CategoryResponse> listForUser(UUID userId) {
        return categoryRepository.findByUserIdOrderByNameAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CategoryResponse update(UUID userId, UUID categoryId, CategoryRequest request) {
        Category category = findOwnedOrThrow(userId, categoryId);
        category.setName(request.name());
        category.setType(request.type());
        return toResponse(category);
    }

    @Transactional
    public void delete(UUID userId, UUID categoryId) {
        Category category = findOwnedOrThrow(userId, categoryId);
        categoryRepository.delete(category);
    }

    /** Usado por outros services (ex.: TransactionService) para validar propriedade sem duplicar lógica. */
    public Category findOwnedOrThrow(UUID userId, UUID categoryId) {
        return categoryRepository.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Categoria"));
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getType().name(),
                category.getCreatedAt());
    }
}
