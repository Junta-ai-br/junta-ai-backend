package br.com.juntaai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class Conversation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(length = 200)
    private String title;

    /**
     * Estado conversacional pendente (PendingOperation e/ou ActionRequest
     * serializados em JSON), persistido entre turnos porque o serviço de
     * IA é stateless — ver AiClient e ConversationService.
     *
     * Sem @Lob de propósito: no PostgreSQL, @Lob em String mapeia para o
     * tipo `oid` (large object), não para `text` — é uma pegadinha comum
     * do Hibernate. columnDefinition="TEXT" é o jeito certo de guardar
     * texto grande no Postgres.
     */
    @Column(name = "pending_state", columnDefinition = "TEXT")
    private String pendingState;
}
