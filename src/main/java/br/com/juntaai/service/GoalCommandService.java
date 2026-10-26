package br.com.juntaai.service;

import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.dto.goal.GoalResponse;
import br.com.juntaai.exception.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/** HTTP writes use a caller-owned identity. Failed transactions leave no receipt. */
@Service
@RequiredArgsConstructor
public class GoalCommandService {
    private final GoalService goals;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional
    public GoalResponse create(UUID user, UUID key, GoalRequest body) {
        return command(user, key, new Input("create", null, body), () -> goals.create(user, body));
    }

    @Transactional
    public GoalResponse progress(UUID user, UUID key, UUID id, BigDecimal amount) {
        return command(user, key, new Input("progress", id, amount), () -> goals.addProgress(user, id, amount));
    }

    @Transactional
    public void delete(UUID user, UUID key, UUID id) {
        command(user, key, new Input("delete", id, null), () -> { goals.delete(user, id); return null; });
    }

    private GoalResponse command(UUID user, UUID key, Input input, Supplier<GoalResponse> effect) {
        String request = json(input);
        jdbc.update("INSERT INTO goal_command_receipts(user_id,command_id,request_json) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                user, key, request);
        var receipt = jdbc.queryForMap("SELECT request_json,response_json FROM goal_command_receipts WHERE user_id=? AND command_id=? FOR UPDATE", user, key);
        if (!request.equals(receipt.get("request_json"))) throw new ConflictException("goal_command_conflict");
        if (receipt.get("response_json") != null) {
            try { return mapper.readValue((String) receipt.get("response_json"), GoalResponse.class); }
            catch (Exception e) { throw new IllegalStateException("goal_receipt_invalid"); }
        }
        GoalResponse response = effect.get();
        jdbc.update("UPDATE goal_command_receipts SET response_json=? WHERE user_id=? AND command_id=?", json(response), user, key);
        return response;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("goal_receipt_invalid"); }
    }

    private record Input(String operation, UUID goalId, Object body) {}
}
