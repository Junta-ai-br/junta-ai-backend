package br.com.juntaai.controller;

import br.com.juntaai.dto.category.CategoryRequest;
import br.com.juntaai.dto.category.CategoryResponse;
import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
@Tag(name = "Categorias", description = "CRUD de categorias financeiras do usuário autenticado")
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "Criar categoria")
    @PostMapping
    public ResponseEntity<CategoryResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @Valid @RequestBody CategoryRequest body) {
        return ResponseEntity.ok(categoryService.create(user.getId(), body));
    }

    @Operation(summary = "Listar categorias do usuário")
    @GetMapping
    public ResponseEntity<List<CategoryResponse>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(categoryService.listForUser(user.getId()));
    }

    @Operation(summary = "Atualizar categoria")
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> update(@AuthenticationPrincipal AuthenticatedUser user,
                                                     @PathVariable UUID id,
                                                     @Valid @RequestBody CategoryRequest body) {
        return ResponseEntity.ok(categoryService.update(user.getId(), id, body));
    }

    @Operation(summary = "Remover categoria")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        categoryService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
