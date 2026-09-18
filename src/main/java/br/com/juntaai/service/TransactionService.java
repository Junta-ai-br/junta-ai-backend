package br.com.juntaai.service;

import br.com.juntaai.dto.transaction.TransactionRequest;
import br.com.juntaai.dto.transaction.TransactionResponse;
import br.com.juntaai.entity.Category;
import br.com.juntaai.entity.Transaction;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.repository.TransactionRepository;
import br.com.juntaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final CategoryService categoryService;

    @Transactional
    public TransactionResponse create(UUID userId, TransactionRequest request) {
        // Garante que a categoria pertence ao mesmo usuário (evita vazamento entre contas).
        Category category = categoryService.findOwnedOrThrow(userId, request.categoryId());
        User user = userRepository.getReferenceById(userId);

        Transaction transaction = Transaction.builder()
                .user(user)
                .category(category)
                .type(request.type())
                .description(request.description())
                .amount(request.amount())
                .transactionDate(request.transactionDate())
                .build();

        return toResponse(transactionRepository.save(transaction));
    }

    /**
     * @Transactional(readOnly = true) é necessário aqui: sem isso, a
     * sessão do Hibernate fecha antes do .map(this::toResponse) rodar,
     * e toResponse() acessa transaction.getCategory().getName() — uma
     * associação @ManyToOne LAZY, que só carrega de verdade quando
     * alguém lê. Sem sessão aberta, isso estoura LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public Page<TransactionResponse> listForUser(UUID userId, LocalDate from, LocalDate to, Pageable pageable) {
        return transactionRepository
                .findByUserIdAndTransactionDateBetween(userId, from, to, pageable)
                .map(this::toResponse);
    }

    @Transactional
    public TransactionResponse update(UUID userId, UUID transactionId, TransactionRequest request) {
        Transaction transaction = findOwnedOrThrow(userId, transactionId);
        Category category = categoryService.findOwnedOrThrow(userId, request.categoryId());

        transaction.setCategory(category);
        transaction.setType(request.type());
        transaction.setDescription(request.description());
        transaction.setAmount(request.amount());
        transaction.setTransactionDate(request.transactionDate());

        return toResponse(transaction);
    }

    @Transactional
    public void delete(UUID userId, UUID transactionId) {
        Transaction transaction = findOwnedOrThrow(userId, transactionId);
        transactionRepository.delete(transaction);
    }

    private Transaction findOwnedOrThrow(UUID userId, UUID transactionId) {
        return transactionRepository.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Transação"));
    }

    private TransactionResponse toResponse(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getCategory().getId(),
                t.getCategory().getName(),
                t.getType().name(),
                t.getDescription(),
                t.getAmount(),
                t.getTransactionDate(),
                t.getCreatedAt());
    }
}
