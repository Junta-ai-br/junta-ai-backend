package br.com.juntaai.service;

import br.com.juntaai.exception.MessageEntryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class MessageProcessingWindow {
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private final long milliseconds;

    public MessageProcessingWindow(
            @Value("${ai.service.message-processing-window-ms:60000}") long milliseconds,
            @Value("${ai.service.connect-timeout-ms}") long connect,
            @Value("${ai.service.read-timeout-ms}") long read) {
        if (connect <= 0 || read <= 0 || milliseconds < Math.addExact(Math.addExact(connect, read), 5000L)) {
            throw new IllegalArgumentException("message_processing_window_must_exceed_ai_http_timeouts");
        }
        this.milliseconds = milliseconds;
    }

    public long milliseconds() { return milliseconds; }

    public static UUID parseKey(String value) {
        if (value == null) throw new MessageEntryException(HttpStatus.BAD_REQUEST, "idempotency_key_required");
        if (!UUID_V4.matcher(value).matches()) {
            throw new MessageEntryException(HttpStatus.BAD_REQUEST, "idempotency_key_invalid");
        }
        return UUID.fromString(value);
    }
}
