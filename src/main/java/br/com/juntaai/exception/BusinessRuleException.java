package br.com.juntaai.exception;

import org.springframework.http.HttpStatus;

/** Violação de regra de negócio (ex.: valor de meta inválido). */
public class BusinessRuleException extends ApiException {
    public BusinessRuleException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
