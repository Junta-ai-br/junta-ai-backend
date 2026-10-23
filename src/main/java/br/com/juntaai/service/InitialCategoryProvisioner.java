package br.com.juntaai.service;

import br.com.juntaai.entity.Category;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.repository.CategoryRepository;
import br.com.juntaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Approved PRD-006 catalog. All writes share the user's lock with category CRUD. */
@Service
@RequiredArgsConstructor
@Slf4j
public class InitialCategoryProvisioner {
    private final UserRepository users;
    private final CategoryRepository categories;

    private record Standard(String name, CategoryType type) {}
    private static final List<Standard> INITIAL = List.of(
            new Standard("Salário", CategoryType.INCOME),
            new Standard("Reembolso", CategoryType.INCOME),
            new Standard("Renda Extra", CategoryType.INCOME),
            new Standard("Bônus e Premiações", CategoryType.INCOME),
            new Standard("Presentes Recebidos", CategoryType.INCOME),
            new Standard("Alimentação", CategoryType.EXPENSE),
            new Standard("Moradia", CategoryType.EXPENSE),
            new Standard("Transporte", CategoryType.EXPENSE),
            new Standard("Saúde", CategoryType.EXPENSE),
            new Standard("Educação", CategoryType.EXPENSE),
            new Standard("Lazer", CategoryType.EXPENSE),
            new Standard("Streaming", CategoryType.EXPENSE),
            new Standard("Apostas", CategoryType.EXPENSE),
            new Standard("Outros", CategoryType.BOTH));

    public record Result(int created, List<String> conflictingNames) {}

    @Transactional
    public Result provision(UUID userId) {
        var user = users.findLockedById(userId)
                .orElseThrow(() -> new IllegalStateException("initial_category_user_missing"));
        if (user.isInitialCategoriesInitialized()) return new Result(0, List.of());
        var existing = categories.findByUserIdOrderByNameAsc(userId);
        var missing = new ArrayList<Category>();
        var conflicts = new ArrayList<String>();
        for (var standard : INITIAL) {
            var matches = existing.stream()
                    .filter(category -> category.getName().equalsIgnoreCase(standard.name())).toList();
            if (matches.isEmpty()) {
                missing.add(Category.builder().user(user).name(standard.name()).type(standard.type()).build());
            } else if (matches.size() != 1 || matches.getFirst().getType() != standard.type()) {
                // Never rename or retype an existing category, including custom homonyms.
                conflicts.add(standard.name());
            }
        }
        if (!missing.isEmpty()) categories.saveAllAndFlush(missing);
        if (!conflicts.isEmpty()) {
            // No user identifiers, custom names, financial values or raw payloads.
            log.warn("initial_category_type_conflict count={}", conflicts.size());
        }
        // Completion and inserted categories commit or roll back together.
        // Later deletions (even an empty catalog) must never trigger reprovisioning.
        user.setInitialCategoriesInitialized(true);
        users.saveAndFlush(user);
        return new Result(missing.size(), List.copyOf(conflicts));
    }
}
