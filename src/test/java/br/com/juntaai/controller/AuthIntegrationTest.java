package br.com.juntaai.controller;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.auth.AccessCodeRequest;
import br.com.juntaai.dto.auth.AccessCodeVerifyRequest;
import br.com.juntaai.dto.auth.RefreshRequest;
import br.com.juntaai.dto.auth.RegisterCodeRequest;
import br.com.juntaai.dto.auth.RegisterVerifyRequest;
import br.com.juntaai.dto.auth.TokenResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends AbstractIntegrationTest {

    @Test
    void deveCadastrarEmDuasEtapasELogarPorCodigoERenovarTokens() throws Exception {
        String email = uniqueEmail("ana");

        String accessToken = registerAndGetAccessToken("Ana", email, "11999998888");
        assertThat(accessToken).isNotBlank();

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

        String refreshBody = objectMapper.writeValueAsString(new RefreshRequest(loginTokens.refreshToken()));
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(notNullValue()));

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void naoDeveEnviarCodigoDeCadastroParaEmailJaExistente() throws Exception {
        String email = uniqueEmail("dup");
        registerAndGetAccessToken("Duplicado", email, "11999998888");

        mockMvc.perform(post("/auth/register/request-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterCodeRequest(email))))
                .andExpect(status().isConflict());
    }

    @Test
    void naoDeveCriarContaComCodigoErrado() throws Exception {
        String email = uniqueEmail("codeerrado");

        mockMvc.perform(post("/auth/register/request-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterCodeRequest(email))))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/auth/register/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterVerifyRequest("Ana", email, "11999998888", "000000", null, null, null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void naoDeveCriarContaSemConfirmarCodigoAntes() throws Exception {
        String email = uniqueEmail("semcodigo");

        mockMvc.perform(post("/auth/register/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterVerifyRequest("Ana", email, "11999998888", "123456", null, null, null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deveRejeitarCadastroSemWhatsapp() throws Exception {
        String email = uniqueEmail("semwpp");

        mockMvc.perform(post("/auth/register/request-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterCodeRequest(email))))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/auth/register/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterVerifyRequest("Ana", email, "", "123456", null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.whatsapp").value(notNullValue()));
    }
}