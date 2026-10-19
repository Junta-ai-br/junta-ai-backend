package br.com.juntaai.security;

import br.com.juntaai.entity.Role;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static final String STRONG = "segredo-forte-de-teste-com-mais-de-32-caracteres";

    @Test
    void naoDeveIniciarSemSegredo() {
        assertThatThrownBy(() -> new JwtUtil("", 900_000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtUtil(null, 900_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void naoDeveIniciarComSegredoCurtoOuDeExemplo() {
        assertThatThrownBy(() -> new JwtUtil("curto", 900_000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtUtil("troque-esta-chave-em-producao-min-32-chars", 900_000))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtUtil("change-this-secret-in-env-minimum-32-chars-long", 900_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deveMontarUsuarioAutenticadoSoComOToken() {
        JwtUtil jwtUtil = new JwtUtil(STRONG, 900_000);
        UUID id = UUID.randomUUID();

        String token = jwtUtil.generateAccessToken(id, "ana@email.com", Role.USER);
        AuthenticatedUser user = jwtUtil.toAuthenticatedUser(token);

        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getUsername()).isEqualTo("ana@email.com");
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void deveRejeitarTokenAdulteradoExpiradoOuAssinadoComOutraChave() {
        JwtUtil jwtUtil = new JwtUtil(STRONG, 900_000);
        String token = jwtUtil.generateAccessToken(UUID.randomUUID(), "ana@email.com", Role.USER);

        String[] partes = token.split("\\.");
        // 1) conteúdo (payload) alterado, com a assinatura antiga
        String payloadAlterado = partes[1].substring(0, partes[1].length() - 2) + (partes[1].endsWith("AA") ? "BB" : "AA");
        assertThat(jwtUtil.toAuthenticatedUser(partes[0] + "." + payloadAlterado + "." + partes[2])).isNull();
        // 2) começo da assinatura alterado
        String assinaturaAlterada = (partes[2].charAt(0) == 'A' ? 'B' : 'A') + partes[2].substring(1);
        assertThat(jwtUtil.toAuthenticatedUser(partes[0] + "." + partes[1] + "." + assinaturaAlterada)).isNull();
        assertThat(jwtUtil.toAuthenticatedUser("lixo")).isNull();

        JwtUtil expirado = new JwtUtil(STRONG, -1_000);
        String old = expirado.generateAccessToken(UUID.randomUUID(), "ana@email.com", Role.USER);
        assertThat(jwtUtil.toAuthenticatedUser(old)).isNull();

        JwtUtil outraChave = new JwtUtil("outra-chave-forte-com-mais-de-32-caracteres!!", 900_000);
        String forjado = outraChave.generateAccessToken(UUID.randomUUID(), "ana@email.com", Role.ADMIN);
        assertThat(jwtUtil.toAuthenticatedUser(forjado)).isNull();
    }
}