# Guardado — contrato e validação

Branch local: feat/guardado-financial-model, base origin/main 356462d.
Worktree: .worktrees/guardado-financial-model. Sem staging ou commits.

GET /dashboard acrescenta saved (BigDecimal serializado como número decimal JSON),
soma de currentAmount de todas as metas do usuário, incluindo concluídas, sem
filtro de período. balance e Health Score mantêm suas regras anteriores.
O snapshot interno acrescenta current_state.saved.

Excluir uma meta com saldo positivo retorna a regra de negócio de retirada
prévia. A verificação ocorre sob o mesmo PESSIMISTIC_WRITE usado pelos aportes e
retiradas. Nenhuma nova tabela ou migração é necessária.

O envio de chat aceita quickReply opcional com option_id, pending_id e
conversation_id. As opções permitidas são source_registered e source_unregistered.
MessageResponse acrescenta quickReplies, reconstruídas somente para a mensagem
atual da pendência persistida. O backend atribui prompt_message_id e renova o
identificador quando publica um prompt de origem, invalidando botões antigos.

AIServiceRequest encaminha quick_reply; ele é omitido em mensagens textuais.
PendingOperation preserva pending_id, conversation_id, resource_source e
prompt_message_id no JSON existente. O rótulo do botão não é autorização; ele é
resolvido pelo identificador e nunca atende a confirmação financeira explícita.
Retiradas agora também exigem uma proposta idêntica e confirmação explícita no
backend. Aportes e retiradas não criam transações.

Seleções inválidas ou antigas retornam quick_reply_stale. Uma seleção em andamento
com outra chave retorna quick_reply_in_progress. A mesma chave idempotente
recupera a resposta existente, sem reinterpretar ou executar outra operação.

Validação: PostgreSQL descartável real, isolado dos bancos existentes. Execução
ampla com PostgreSQL 16 e AI Service HTTP real: 352 testes executados aprovados,
20 skips, zero falhas/erros, excluindo MessageEntryMigrationPostgresTest. Esse
teste antigo falha por esperar uma migração após V5 embora a base já tenha sete;
a expectativa não foi alterada. GuardadoPostgresTest cobre soma exata, usuários,
metas concluídas e as duas ordens de concorrência aporte/exclusão.
GoalConversationContractPostgresTest cobre oito fluxos reais entre serviços.
GuardadoContractsTest verifica a extensão opcional do transporte.

Integrar em conjunto com feat/guardado-conversation do AI Service, depois o
frontend feat/guardado-dashboard. O backend antigo descarta os novos metadados
de pendência, portanto não sustenta sozinho o novo fluxo. Não há saldo inicial,
conciliação ou lançamento automático de receita/despesa nesta entrega.
