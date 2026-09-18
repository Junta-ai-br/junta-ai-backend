package br.com.juntaai.exception;

import org.springframework.http.HttpStatus;

/** Exceção base de negócio. Toda exceção de aplicação carrega o HttpStatus correto. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
