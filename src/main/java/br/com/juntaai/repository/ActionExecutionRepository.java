package br.com.juntaai.repository;

import br.com.juntaai.entity.ActionExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import java.util.UUID;

public interface ActionExecutionRepository extends JpaRepository<ActionExecution, UUID> {
    boolean existsByUserIdAndConversationIdAndCompositionPendingTrueAndActionIdNot(
            UUID userId, UUID conversationId, String actionId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ActionExecution> findByActionId(String actionId);
    Optional<ActionExecution> findFirstByUserIdAndConversationIdAndCompositionPendingTrueOrderByCreatedAtAsc(
            UUID userId, UUID conversationId);
}
