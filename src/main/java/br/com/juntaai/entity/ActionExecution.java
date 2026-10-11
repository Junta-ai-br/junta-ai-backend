package br.com.juntaai.entity;

import br.com.juntaai.integration.ai.model.ExecutionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.UUID;

/** Internal evidence. Never serialize this entity or log its financial JSON. */
@Entity
@Table(name = "action_executions")
@Getter @Setter @NoArgsConstructor
public class ActionExecution extends BaseEntity {
    @Column(name = "action_id", nullable = false, unique = true, length = 200)
    private String actionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "conversation_id", nullable = false) private UUID conversationId;
    @Column(name = "user_message_id", nullable = false) private UUID userMessageId;
    @Column(name = "request_id", nullable = false, length = 200) private String requestId;
    @Column(name = "action_json", nullable = false, columnDefinition = "TEXT") private String actionJson;
    @Column(name = "receipt_json", columnDefinition = "TEXT") private String receiptJson;
    @Enumerated(EnumType.STRING) @Column(length = 20) private ExecutionStatus status;
    @Column(name = "effect_id") private UUID effectId;
    @Column(name = "pending_state_before", columnDefinition = "TEXT") private String pendingStateBefore;
    @Column(name = "composition_pending", nullable = false) private boolean compositionPending;
}
