package br.com.juntaai.repository;

import br.com.juntaai.entity.EmailAccessCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EmailAccessCodeRepository extends JpaRepository<EmailAccessCode, UUID> {
    List<EmailAccessCode> findByEmailAndUsedFalseOrderByCreatedAtDesc(String email);
}
