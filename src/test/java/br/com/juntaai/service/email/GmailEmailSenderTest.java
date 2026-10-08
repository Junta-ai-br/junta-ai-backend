package br.com.juntaai.service.email;

import br.com.juntaai.exception.ApiException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GmailEmailSenderTest {

    @Mock private JavaMailSender mailSender;
    private GmailEmailSender sender;

    @BeforeEach
    void setUp() {
        sender = new GmailEmailSender(mailSender, "junta@example.com", 7);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void deveEnviarMensagemComDestinatarioRemetenteCodigoEExpiracaoConfigurados() throws Exception {
        sender.sendAccessCode("ana@example.com", "012345");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("ana@example.com");
        assertThat(message.getFrom()).extracting(Object::toString).containsExactly("junta@example.com");
        assertThat(message.getSubject()).isEqualTo("Seu código de acesso ao Junta.ai").contains("código de acesso");
        assertThat(message.getContent().toString())
                .contains("Seu código de acesso ao Junta.ai é: 012345", "Ele vale por 7 minutos.",
                        "Se você não pediu esse código, pode ignorar este e-mail.");
        assertThat(message.getContentType()).containsIgnoringCase("charset=UTF-8");
    }

    @Test
    void deveConverterFalhaDoProvedorEm503SemExporDadosSensiveis() {
        doThrow(new MailSendException("ana@example.com OTP=012345 senha=secreta"))
                .when(mailSender).send(any(MimeMessage.class));

        assertSafeFailure();
    }

    @Test
    void deveConverterFalhaDeAutenticacaoDoSmtpEm503() {
        doThrow(new MailAuthenticationException("credenciais sensíveis"))
                .when(mailSender).send(any(MimeMessage.class));

        assertSafeFailure();
    }

    @Test
    void deveConverterErroAoMontarMensagemEm503() {
        assertThatThrownBy(() -> sender.sendAccessCode("endereco@@invalido", "012345"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private void assertSafeFailure() {
        assertThatThrownBy(() -> sender.sendAccessCode("ana@example.com", "012345"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Não foi possível enviar o código por e-mail agora. Tente novamente em instantes.")
                .hasNoCause()
                .satisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
