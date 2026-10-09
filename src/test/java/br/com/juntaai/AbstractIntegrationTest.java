package br.com.juntaai;

import br.com.juntaai.dto.auth.RegisterCodeRequest;
import br.com.juntaai.dto.auth.RegisterVerifyRequest;
import br.com.juntaai.dto.auth.TokenResponse;
import br.com.juntaai.service.email.EmailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "management.health.mail.enabled=false")
@EnableConfigurationProperties(MailProperties.class)
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @MockBean
    protected EmailSender emailSender;

    /** Bloqueia qualquer envio SMTP real, inclusive pelos formulários públicos. */
    @MockBean
    protected JavaMailSender javaMailSender;

    /**
     * Executa o cadastro completo (passo 1 + passo 2) e devolve o
     * access token. Captura o código real gerado via o mock do
     * EmailSender — não dá pra prever o código, é aleatório.
     */
    protected String registerAndGetAccessToken(String name, String email, String whatsapp) throws Exception {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        doAnswer(inv -> null).when(emailSender).sendAccessCode(any(), codeCaptor.capture());

        mockMvc.perform(post("/auth/register/request-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterCodeRequest(email))))
                .andExpect(status().isAccepted());

        String code = codeCaptor.getValue();

        String responseBody = mockMvc.perform(post("/auth/register/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterVerifyRequest(name, email, whatsapp, code, null, null, null))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(responseBody, TokenResponse.class).accessToken();
    }

    protected String uniqueEmail(String prefix) {
        return prefix + "+" + System.nanoTime() + "@teste.com";
    }
}
