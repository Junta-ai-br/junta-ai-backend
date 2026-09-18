package br.com.juntaai.controller;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.auth.AccessCodeRequest;
import br.com.juntaai.dto.auth.AccessCodeVerifyRequest;
import br.com.juntaai.dto.auth.RefreshRequest;
import br.com.juntaai.dto.auth.RegisterRequest;
import br.com.juntaai.dto.auth.TokenResponse;
import br.com.juntaai.service.email.EmailSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @MockBean no EmailSender: em produção ele manda e-mail de verdade, aqui
 * só capturamos o código gerado pra poder completar o fluxo de login no
 * teste, sem depender de nenhum provedor de e-mail real.
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    @MockBean
    private EmailSender emailSender;

    @Test
    void deveRegistrarLogarPorCodigoERenovarTokens() throws Exception {
        String email = uniqueEmail("ana");

        // cadastro: já devolve tokens, sem precisar de código
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("Ana", email, "11999998888", null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(org.hamcrest.Matchers.notNullValue()));

        // login por código: captura o código real gerado (não dá pra prever, é aleatório)
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        doAnswer(inv -> null).when(emailSender).sendAccessCode(any(), codeCaptor.capture());

        mockMvc.perform(post("/auth/access-code/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccessCodeRequest(email))))
                .andExpect(status().isAccepted());

        String code = codeCaptor.getValue();

        String responseBody = mockMvc.perform(post("/auth/access-code/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccessCodeVerifyRequest(email, code))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        TokenResponse loginTokens = objectMapper.readValue(responseBody, TokenResponse.class);
        assertThat(loginTokens.accessToken()).isNotBlank();

        // refresh: o token antigo funciona uma vez...
        String refreshBody = objectMapper.writeValueAsString(new RefreshRequest(loginTokens.refreshToken()));
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(org.hamcrest.Matchers.notNullValue()));

        // ...e a segunda tentativa com o MESMO refresh token antigo deve falhar (rotação).
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void naoDevePermitirRegistroComEmailDuplicado() throws Exception {
        String email = uniqueEmail("dup");
        RegisterRequest request = new RegisterRequest("Duplicado", email, "11999998888", null, null, null);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void deveRejeitarCodigoErradoNoLogin() throws Exception {
        String email = uniqueEmail("codeerrado");
        registerAndGetAccessToken("Teste", email, "11999998888");

        mockMvc.perform(post("/auth/access-code/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccessCodeRequest(email))))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/auth/access-code/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccessCodeVerifyRequest(email, "000000"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deveRejeitarCadastroSemWhatsapp() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("Ana", uniqueEmail("semwpp"), "", null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.whatsapp").value(org.hamcrest.Matchers.notNullValue()));
    }
}
