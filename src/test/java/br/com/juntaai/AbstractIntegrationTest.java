package br.com.juntaai;

import br.com.juntaai.dto.auth.RegisterRequest;
import br.com.juntaai.dto.auth.TokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base para testes de integração: bate nos endpoints HTTP através do
 * MockMvc, passando pela cadeia de segurança completa (JWT, filtros,
 * etc.), contra um PostgreSQL real.
 *
 * IMPORTANTE — mudança em relação à versão anterior: não usamos mais
 * Testcontainers para subir o banco automaticamente. A biblioteca
 * docker-java (usada por dentro do Testcontainers) tem um bug de
 * compatibilidade com Docker Engine muito recente (>= 1.55): a etapa de
 * "descoberta" do Docker sempre tenta a API na versão 1.32, ignorando
 * DOCKER_API_VERSION, e engines novos recusam isso.
 *
 * Solução pragmática: os testes agora usam o Postgres do `compose.yaml`
 * (o `docker` CLI comum funciona normalmente, só a biblioteca Java que
 * tem o bug). Rode ANTES de `mvn test`:
 *
 *     docker compose up postgres -d
 *
 * O application.yml já aponta para localhost:5432/junta_ai por padrão,
 * que é exatamente o que esse serviço expõe — nenhuma configuração
 * adicional é necessária.
 *
 * Trade-off: como o banco não é recriado do zero a cada execução, os
 * testes precisam continuar gerando dados únicos por execução (e já
 * fazem isso via uniqueEmail()) para não colidir com dados de rodadas
 * anteriores.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * Registra um usuário novo (e-mail único por chamada) e devolve o
     * access token pronto pra usar — não há mais senha; o cadastro já
     * emite os tokens direto (ver AuthService.register).
     */
    protected String registerAndGetAccessToken(String name, String email, String whatsapp) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(name, email, whatsapp, null, null, null));

        String responseBody = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(responseBody, TokenResponse.class).accessToken();
    }

    /** Gera um e-mail único por teste, evitando colisão de UNIQUE constraint entre execuções. */
    protected String uniqueEmail(String prefix) {
        return prefix + "+" + System.nanoTime() + "@teste.com";
    }
}
