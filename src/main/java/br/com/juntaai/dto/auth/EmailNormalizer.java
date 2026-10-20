package br.com.juntaai.dto.auth;

import java.util.Locale;

/**
 * Padroniza o e-mail (sem espaços nas pontas e em minúsculas) logo na
 * entrada da API. Sem isso, "Ana@Email.com" e "ana@email.com" virariam
 * contas diferentes e o código enviado para um não valeria para o outro.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}