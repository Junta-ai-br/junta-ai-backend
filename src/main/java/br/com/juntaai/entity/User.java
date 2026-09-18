package br.com.juntaai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class User extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 180)
    @EqualsAndHashCode.Include
    private String email;

    @Column(length = 20)
    private String whatsapp;

    /**
     * Não há mais autenticação por senha local (ver docs/security.md —
     * decisão do time: Google ou chave de acesso por e-mail). Coluna
     * mantida nullable só por compatibilidade com dados antigos; novos
     * usuários nunca preenchem isso.
     */
    @Column(name = "password_hash")
    private String passwordHash;

    /** Preenchido quando o usuário entra pela primeira vez via Google. */
    @Column(name = "google_id", unique = true)
    private String googleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.USER;
}
