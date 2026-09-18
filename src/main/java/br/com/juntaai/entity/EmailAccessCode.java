package br.com.juntaai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Código numérico de 6 dígitos enviado por e-mail para login sem senha.
 * Guardamos o hash (BCrypt), não o valor em texto puro — mesmo sendo de
 * curta duração, é o mesmo cuidado que já tínhamos com senha.
 */
@Entity
@Table(name = "email_access_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class EmailAccessCode extends BaseEntity {

    @Column(nullable = false, length = 180)
    private String email;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Builder.Default
    private boolean used = false;
}
