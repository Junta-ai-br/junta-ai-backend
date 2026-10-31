package br.com.juntaai.integration.ai.model;

public record QuickReplySelection(String option_id, String pending_id, String conversation_id) {
    public String label() {
        return switch (option_id == null ? "" : option_id) {
            case "source_registered" -> "Sim, já registrei";
            case "source_unregistered" -> "Não, é dinheiro novo";
            default -> throw new br.com.juntaai.exception.MessageEntryException(
                    org.springframework.http.HttpStatus.CONFLICT, "quick_reply_stale");
        };
    }
}
