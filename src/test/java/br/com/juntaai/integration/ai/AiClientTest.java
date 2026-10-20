package br.com.juntaai.integration.ai;

import br.com.juntaai.exception.AiServiceUnavailableException;
import br.com.juntaai.integration.ai.model.AIServiceRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.http.HttpMethod.POST;

class AiClientTest {
    private static final String ID = "99a6b362-08b7-4e35-8822-dc6431d61941";
    private static final String SECRET = "sensitive-user-text-token-credential";
    private final Logger logger = (Logger) LoggerFactory.getLogger(AiClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach void setUp() {
        logs.start();
        logger.addAppender(logs);
        builder = RestClient.builder().baseUrl("http://ai.invalid");
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @AfterEach void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private AIServiceRequest request(String id) {
        return new AIServiceRequest(id, "1", "conversation", SECRET, null, null, null, null, null);
    }

    private AiClient client() {
        AiClient client = new AiClient(builder.build());
        ReflectionTestUtils.setField(client, "processPath", "/v1/conversations/process");
        return client;
    }

    private void fails(AiClient client, String id, String category, String status) {
        assertThatThrownBy(() -> client.process(request(id)))
                .isInstanceOfSatisfying(AiServiceUnavailableException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getMessage()).isEqualTo(
                            "O assistente est\u00e1 indispon\u00edvel no momento. Tente novamente em instantes.");
                    assertThat(ex.getCause()).isNull();
                });
        assertThat(logs.list).hasSize(1);
        ILoggingEvent event = logs.list.getFirst();
        assertThat(event.getFormattedMessage()).contains("ai_service_unavailable", "category=" + category,
                "http_status=" + status, "request_id=" + (ID.equals(id) ? ID : "unavailable"))
                .doesNotContain(SECRET, "Authorization", "http://ai.invalid");
        assertThat(event.getFormattedMessage()).containsPattern("duration_ms=\\d+");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 422, 500, 503})
    void httpErrorsKeepStatusAndNeverLogBody(int status) {
        server.expect(requestTo("http://ai.invalid/v1/conversations/process"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body(SECRET).contentType(MediaType.APPLICATION_JSON));
        fails(client(), ID, "http_error", Integer.toString(status));
        server.verify(); // Exactly one request: no retry.
    }

    @Test void malformedResponseRecordsSuccessfulHttpStatusWithoutBody() {
        server.expect(requestTo("http://ai.invalid/v1/conversations/process"))
                .andRespond(withSuccess("{" + SECRET, MediaType.APPLICATION_JSON));
        fails(client(), ID, "response_deserialization", "200");
        server.verify();
    }

    @Test void unsupportedResponseContentTypeIsDeserializationFailure() {
        server.expect(requestTo("http://ai.invalid/v1/conversations/process"))
                .andRespond(withSuccess(SECRET, MediaType.TEXT_PLAIN));
        fails(client(), ID, "response_deserialization", "200");
        server.verify();
    }

    @Test void transportFailureDoesNotInventHttpStatus() {
        server.expect(requestTo("http://ai.invalid/v1/conversations/process"))
                .andRespond(withException(new ConnectException(SECRET)));
        fails(client(), ID, "transport", "unknown");
        server.verify();
    }

    @Test void timeoutDoesNotInventHttpStatus() {
        server.expect(requestTo("http://ai.invalid/v1/conversations/process"))
                .andRespond(withException(new SocketTimeoutException(SECRET)));
        fails(client(), ID, "timeout", "unknown");
        server.verify();
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new HttpMessageNotWritableException(SECRET), "request_serialization"),
                Arguments.of(new RestClientException(SECRET, new HttpMessageNotWritableException(SECRET)), "request_serialization"),
                Arguments.of(new RestClientException(SECRET, new HttpMessageNotReadableException(
                        SECRET, new MockHttpInputMessage(new byte[0]))), "response_deserialization"),
                Arguments.of(new ResourceAccessException(SECRET, new HttpTimeoutException(SECRET)), "timeout"),
                Arguments.of(new IllegalStateException(SECRET), "unexpected"));
    }

    @ParameterizedTest @MethodSource("failures")
    void classifiesWrappedFailuresWithoutExceptionDetails(RuntimeException failure, String category) {
        builder.requestInterceptor((request, body, execution) -> { throw failure; });
        fails(client(), ID, category, "unknown");
    }

    @Test void unsafeCorrelationIdCannotInjectLogContent() {
        builder.requestInterceptor((request, body, execution) -> { throw new IllegalStateException(SECRET); });
        fails(client(), SECRET + "\nAuthorization: token", "unexpected", "unknown");
    }

    @Test void successfulResponseIsReturnedWithoutFailureLog() {
        server.expect(requestTo("http://ai.invalid/v1/conversations/process"))
                .andRespond(withSuccess("{\"request_id\":\"" + ID
                        + "\",\"contract_version\":\"1\",\"decision\":{\"type\":\"response\"}}", MediaType.APPLICATION_JSON));
        var result = client().process(request(ID));
        assertThat(result.request_id()).isEqualTo(ID);
        assertThat(result.contract_version()).isEqualTo("1");
        assertThat(logs.list).isEmpty();
        server.verify();
    }

    @Test void emptyResponseRemainsNullForExistingCallerValidation() {
        server.expect(requestTo("http://ai.invalid/v1/conversations/process")).andRespond(withNoContent());
        assertThat(client().process(request(ID))).isNull();
        assertThat(logs.list).isEmpty();
        server.verify();
    }
}
