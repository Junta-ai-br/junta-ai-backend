package br.com.juntaai.repository;

import br.com.juntaai.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);
    Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);
}
