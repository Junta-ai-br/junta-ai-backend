package br.com.juntaai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Respostas das 3 perguntas de onboarding da Etapa 2 do cadastro.
 * Totalmente opcional (o usuário pode "Pular por agora") — por isso as
 * três colunas são nullable e o registro sequer é criado se tudo vier
 * vazio.
 */
@Entity
@Table(name = "onboarding_answers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class OnboardingAnswers extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "question_1", columnDefinition = "TEXT")
    private String question1;

    @Column(name = "question_2", columnDefinition = "TEXT")
    private String question2;

    @Column(name = "question_3", columnDefinition = "TEXT")
    private String question3;
}
