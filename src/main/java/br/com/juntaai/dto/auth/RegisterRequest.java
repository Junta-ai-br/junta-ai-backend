package br.com.juntaai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Espelha a Etapa 1 + Etapa 2 do cadastro do frontend
 * (src/components/Cadastro/Cadastro.jsx). As perguntas de onboarding são
 * opcionais — o usuário pode "Pular por agora", enviando null/vazio.
 */
public record RegisterRequest(

        @NotBlank(message = "O nome é obrigatório.")
        @Size(min = 2, max = 150, message = "O nome deve ter entre 2 e 150 caracteres.")
        String name,

        @NotBlank(message = "O e-mail é obrigatório.")
        @Email(message = "Informe um e-mail válido.")
        String email,

        @NotBlank(message = "O WhatsApp é obrigatório.")
        @Size(max = 20, message = "Número de WhatsApp inválido.")
        String whatsapp,

        String question1,
        String question2,
        String question3
) {}
