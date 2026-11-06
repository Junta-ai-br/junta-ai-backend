package br.com.juntaai.integration.ai.model;

@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record QuickReplySelection(String option_id, String pending_id, String conversation_id, String display_label) {
    public QuickReplySelection(String option, String pending, String conversation) { this(option, pending, conversation, null); }
    public String label() {
        return switch (option_id == null ? "" : option_id) {
            case "source_registered" -> "Sim, já registrei";
            case "source_unregistered" -> "Não, é dinheiro novo";
            case "confirm" -> "confirmo";
            case "cancel" -> "cancelar";
            default -> {
                if (option_id != null && option_id.startsWith("goal:")) {
                    java.util.UUID.fromString(option_id.substring(5));
                    yield option_id;
                }
                throw new br.com.juntaai.exception.MessageEntryException(org.springframework.http.HttpStatus.CONFLICT, "quick_reply_stale");
            }
        };
    }
}
