package br.com.juntaai.service;

import br.com.juntaai.integration.ai.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GuardadoContractsTest {
    @Test void legacyTextRequestOmitsOptionalQuickReplyAndSelectionUsesStableIdentifiers() {
        var mapper = new ObjectMapper();
        var legacy = new AIServiceRequest("request", "1", "conversation", "texto", null, null, null, null, null);
        assertThat(mapper.valueToTree(legacy).has("quick_reply")).isFalse();
        var selection = new QuickReplySelection("source_registered", "pending", "conversation");
        var structured = new AIServiceRequest("request", "1", "conversation", "texto", null, null, null, null, null, selection);
        var wire = mapper.valueToTree(structured).path("quick_reply");
        assertThat(wire.path("option_id").asText()).isEqualTo("source_registered");
        assertThat(wire.path("pending_id").asText()).isEqualTo("pending");
        assertThat(wire.path("conversation_id").asText()).isEqualTo("conversation");
        assertThat(wire.size()).isEqualTo(3);
    }
}
