package br.com.juntaai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/register/verify — segundo passo: confirma o código
 * recebido por e-mail e só então grava a conta de verdade.
 */
public record RegisterVerifyRequest(
        @NotBlank(message = "O nome é obrigatório.")
        @Size(min = 2, max = 150, message = "O nome deve ter entre 2 e 150 caracteres.")
        String name,

        @NotBlank(message = "O e-mail é obrigatório.")
        @Email(message = "Informe um e-mail válido.")
        String email,

        @NotBlank(message = "O WhatsApp é obrigatório.")
        @Size(max = 20, message = "Número de WhatsApp inválido.")
        String whatsapp,

        @NotBlank(message = "O código é obrigatório.")
        @Pattern(regexp = "\\d{6}", message = "O código deve ter exatamente 6 dígitos.")
        String code,

        String question1,
        String question2,
        String question3
) {}