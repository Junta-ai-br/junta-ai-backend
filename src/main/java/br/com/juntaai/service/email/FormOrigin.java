package br.com.juntaai.service.email;

import br.com.juntaai.dto.form.PublicFormRequest;
import br.com.juntaai.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

public enum FormOrigin {
    CONTACT("Contato", Map.of(
            "faq", "Não encontrei minha dúvida na FAQ", "junta", "Dúvida sobre o Junta.ai",
            "sugestao", "Sugestão ou ideia", "parceria", "Parceria ou colaboração",
            "imprensa", "Imprensa", "outro", "Outro")),
    FEEDBACK("Feedback", Map.of(
            "experiencia", "Experiência com o Junta.ai", "sugestao", "Sugestão ou nova ideia",
            "problema", "Encontrei um problema", "duvida", "Algo não ficou claro",
            "privacidade", "Privacidade e segurança", "financeiro", "Recursos financeiros",
            "assistente", "Assistente / IA", "outro", "Outro"));

    private final String label;
    private final Map<String, String> subjects;

    FormOrigin(String label, Map<String, String> subjects) {
        this.label = label;
        this.subjects = subjects;
    }

    public String label() { return label; }

    public String subjectLabel(PublicFormRequest request) {
        String subject = subjects.get(request.subject());
        if (subject == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Assunto inválido para este formulário.");
        }
        if ("outro".equals(request.subject())) {
            if (request.subjectOther() == null || request.subjectOther().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Informe o assunto em subjectOther.");
            }
            return request.subjectOther();
        }
        return subject;
    }
}
