package br.com.juntaai.controller;

import br.com.juntaai.dto.form.PublicFormRequest;
import br.com.juntaai.service.email.FormOrigin;
import br.com.juntaai.service.email.PublicFormMailService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PublicFormController {
    private final PublicFormMailService service;

    public record SentResponse(String status) {}

    @PostMapping("/contact")
    public SentResponse contact(@Valid @RequestBody PublicFormRequest request) {
        service.send(FormOrigin.CONTACT, request);
        return new SentResponse("sent");
    }

    @PostMapping("/feedback")
    public SentResponse feedback(@Valid @RequestBody PublicFormRequest request) {
        service.send(FormOrigin.FEEDBACK, request);
        return new SentResponse("sent");
    }
}
