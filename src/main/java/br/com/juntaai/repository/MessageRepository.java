package br.com.juntaai.repository;

import br.com.juntaai.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** Só as N mensagens mais recentes (passe PageRequest com Sort por createdAt DESC). */
    List<Message> findByConversationId(UUID conversationId, Pageable pageable);
}
