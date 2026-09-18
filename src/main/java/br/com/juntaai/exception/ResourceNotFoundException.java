package br.com.juntaai.exception;

import org.springframework.http.HttpStatus;

/**
 * Usada tanto para "não existe" quanto para "existe mas não pertence ao
 * usuário autenticado". Não diferenciamos as duas situações na resposta
 * HTTP para não revelar a existência de recursos de outros usuários.
 */
public class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException(String resource) {
        super(HttpStatus.NOT_FOUND, resource + " não encontrado(a).");
    }
}
