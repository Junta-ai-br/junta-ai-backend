package br.com.juntaai.controller;

import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.dto.conversation.ChatReplyResponse;
import br.com.juntaai.dto.conversation.ConversationResponse;
import br.com.juntaai.dto.conversation.MessageResponse;
import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
@Tag(name = "Chat / IA", description = "Conversas e mensagens — integra com o serviço junta-ai-ai via AiClient")
public class ConversationController {

    private final ConversationService conversationService;

    @Operation(summary = "Criar conversa")
    @PostMapping
    public ResponseEntity<ConversationResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                         @RequestBody(required = false) Map<String, String> body) {
        String title = body != null ? body.get("title") : null;
        return ResponseEntity.ok(conversationService.createConversation(user.getId(), title));
    }

    @Operation(summary = "Listar conversas do usuário")
    @GetMapping
    public ResponseEntity<List<ConversationResponse>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(conversationService.listForUser(user.getId()));
    }

    @Operation(summary = "Listar mensagens de uma conversa")
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageResponse>> messages(@AuthenticationPrincipal AuthenticatedUser user,
                                                            @PathVariable UUID id) {
        return ResponseEntity.ok(conversationService.listMessages(user.getId(), id));
    }

    @Operation(summary = "Enviar mensagem e receber resposta da IA (CrewAI + MCP)")
    @PostMapping("/{id}/messages")
    public ResponseEntity<ChatReplyResponse> sendMessage(@AuthenticationPrincipal AuthenticatedUser user,
                                                          @PathVariable UUID id,
                                                          @Valid @RequestBody ChatMessageRequest body) {
        return ResponseEntity.ok(conversationService.sendMessage(user.getId(), id, body));
    }
}
