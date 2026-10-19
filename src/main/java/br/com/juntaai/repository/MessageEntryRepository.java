package br.com.juntaai.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/** Specific to chat ingress. All locking callers first lock the owned conversation. */
@Repository
@RequiredArgsConstructor
public class MessageEntryRepository {
    private final JdbcTemplate jdbc;

    public record Entry(UUID id, UUID userId, UUID conversationId, UUID messageId, String mode, String state,
                        String requestId, String pendingBefore, String actionId, String actionJson,
                        String receiptId, UUID responseId, Instant deadline) {
        public boolean recovering() { return "RECOVERY_ONLY".equals(mode); }
    }

    private static final RowMapper<Entry> ROW = (r, n) -> new Entry(
            r.getObject("id", UUID.class), r.getObject("user_id", UUID.class),
            r.getObject("conversation_id", UUID.class), r.getObject("user_message_id", UUID.class),
            r.getString("mode"), r.getString("state"), r.getString("interpretation_request_id"),
            r.getString("pending_state_before"), r.getString("selected_action_id"), r.getString("selected_action_json"),
            r.getString("receipt_action_id"), r.getObject("response_message_id", UUID.class),
            r.getTimestamp("processing_deadline").toInstant());

    public Entry byKey(UUID user, UUID key) {
        return jdbc.query("SELECT * FROM conversation_message_entries WHERE user_id=? AND idempotency_key=? FOR UPDATE",
                ROW, user, key).stream().findFirst().orElse(null);
    }

    public Entry byMessage(UUID message) {
        return jdbc.query("SELECT * FROM conversation_message_entries WHERE user_message_id=? FOR UPDATE",
                ROW, message).stream().findFirst().orElse(null);
    }

    public boolean reserve(UUID id, UUID user, UUID conversation, UUID key, UUID message, String pending,
                           String receipt, long window) {
        return jdbc.update("""
                INSERT INTO conversation_message_entries(id,user_id,idempotency_key,conversation_id,user_message_id,
                    mode,state,interpretation_request_id,pending_state_before,receipt_action_id,processing_deadline)
                VALUES (?,?,?,?,?,?,?, ?,?,?,clock_timestamp() + (? * interval '1 millisecond'))
                ON CONFLICT (user_id,idempotency_key) DO NOTHING
                """, id, user, key, conversation, message, receipt == null ? "NORMAL" : "RECOVERY_ONLY",
                receipt == null ? "PROCESSING" : "RECEIPT_PENDING", UUID.randomUUID().toString(), pending, receipt, window) == 1;
    }

    public boolean initialExpired(Entry entry) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT clock_timestamp() >= processing_deadline FROM conversation_message_entries WHERE id=?",
                Boolean.class, entry.id()));
    }

    public void failInitial(Entry entry) {
        jdbc.update("""
                UPDATE conversation_message_entries SET state='FAILED',failure_code='message_processing_failed',updated_at=clock_timestamp()
                WHERE id=? AND state='PROCESSING' AND selected_action_id IS NULL AND receipt_action_id IS NULL
                """, entry.id());
    }

    public boolean select(Entry entry, String actionId, String json) {
        return jdbc.update("""
                UPDATE conversation_message_entries SET state='ACTION_SELECTED',selected_action_id=?,selected_action_json=?,updated_at=clock_timestamp()
                WHERE id=? AND state='PROCESSING' AND clock_timestamp() < processing_deadline
                """, actionId, json, entry.id()) == 1;
    }

    public void bindReceipt(Entry entry, String actionId) {
        jdbc.update("""
                UPDATE conversation_message_entries SET state='RECEIPT_PENDING',receipt_action_id=?,selected_action_json=NULL,updated_at=clock_timestamp()
                WHERE id=? AND state='ACTION_SELECTED'
                """, actionId, entry.id());
    }

    public void finish(Entry entry, UUID responseId, boolean complete) {
        jdbc.update("""
                UPDATE conversation_message_entries SET response_message_id=?,state=?,updated_at=clock_timestamp() WHERE id=?
                """, responseId, complete ? "COMPLETED" : "RECEIPT_PENDING", entry.id());
    }
}
