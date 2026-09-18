package br.com.juntaai.repository;

import br.com.juntaai.entity.OnboardingAnswers;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OnboardingAnswersRepository extends JpaRepository<OnboardingAnswers, UUID> {
    Optional<OnboardingAnswers> findByUserId(UUID userId);
}
