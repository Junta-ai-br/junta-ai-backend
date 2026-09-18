package br.com.juntaai.dto.category;

import br.com.juntaai.entity.CategoryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CategoryRequest(

        @NotBlank(message = "O nome da categoria é obrigatório.")
        @Size(min = 2, max = 120, message = "O nome deve ter entre 2 e 120 caracteres.")
        String name,

        @NotNull(message = "O tipo da categoria é obrigatório.")
        CategoryType type
) {}
