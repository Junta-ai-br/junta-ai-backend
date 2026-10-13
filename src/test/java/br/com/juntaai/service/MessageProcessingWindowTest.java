package br.com.juntaai.service;

import br.com.juntaai.exception.MessageEntryException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MessageProcessingWindowTest {
    @Test void windowMustAccountForConfiguredHttpTimeouts() {
        assertThat(new MessageProcessingWindow(60000, 3000, 15000).milliseconds()).isEqualTo(60000);
        assertThatThrownBy(() -> new MessageProcessingWindow(60000, 10000, 50000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new MessageProcessingWindow(65000, 10000, 50000).milliseconds()).isEqualTo(65000);
    }

    @Test void onlyCanonicalUuidV4IsAccepted() {
        UUID key = UUID.randomUUID();
        assertThat(MessageProcessingWindow.parseKey(key.toString().toUpperCase())).isEqualTo(key);
        for (String invalid : new String[]{"", " " + key, key + "," + key, "00000000-0000-1000-8000-000000000000", "1-1-4-8-1"}) {
            assertThatThrownBy(() -> MessageProcessingWindow.parseKey(invalid)).isInstanceOf(MessageEntryException.class);
        }
        assertThatThrownBy(() -> MessageProcessingWindow.parseKey(null)).isInstanceOf(MessageEntryException.class)
                .extracting("code").isEqualTo("idempotency_key_required");
    }
}
