# Validação complementar de bordas — PRs #55 e #47

**Recomendação: aptas ao merge quanto aos dois cenários solicitados.** Nenhuma falha real identificada, nenhuma correção de produção necessária. Este relatório registra a validação complementar anterior à publicação. O usuário autorizou posteriormente a inclusão destes testes e da documentação nas PRs existentes; merge e deploy continuam exigindo aprovação separada.

## Referências e escopo

PR AI: https://github.com/Junta-ai-br/junta-ai-ai/pull/55 — head 89e6dbe0257fc89505db8e298da8aaa5ed5dda9c.
PR backend: https://github.com/Junta-ai-br/junta-ai-backend/pull/47 — head 451e1f2761b03a321bccee313159a20df449be86.
Heads remotos consultados e correspondentes às worktrees existentes `transaction-preconfirmation-stage1`. Nenhuma alteração de produção, contratos, frontend, CRUD externo ou etapa 2 de datas. Trabalhos locais anteriores preservados.

## 1. Recibo pendente e seleção Confirmar — PROTEGIDO

`MessageEntryStore.java:50` identifica recibo pendente para o mesmo usuário/conversa sob lock da conversa. Ao recuperar, não reserva outra proposta (`:104`), mas persiste a seleção recebida na entrada de recuperação (`:123`). Essa seleção não é autorização para outra execução: a entrada nasce como RECOVERY_ONLY/RECEIPT_PENDING, ligada ao action_id anterior (`MessageEntryRepository.java:48`).

`ConversationService.java:81` encaminha essa entrada diretamente à composição. O pedido de composição usa exclusivamente mensagem, ação e confirmação originais do recibo (`:166`); não repassa a nova seleção para interpretação. `MessageEntryRepository.java:77` só permite selecionar ação em PROCESSING, e o executor rejeita ingressos de recuperação (`ActionExecutionService.java:59`). Portanto, a ausência de nova confirmation_proposal_id nesse caminho é intencional: ele só recupera um efeito já executado, sem autorizar um novo.

Testes PostgreSQL novos, com AI controlado e transações reais:

- Duas seleções simultâneas, chaves diferentes e barreiras determinísticas: ambas ficam em RECOVERY_ONLY, referenciam o mesmo recibo anterior, não possuem selected_action_id nem nova reserva e retornam a mesma mensagem de composição. Apenas uma transação e uma reserva financeira existem. Replays conservam os resultados; uma chave nova com seleção antiga depois da conclusão é rejeitada.
- Proposta mais recente sem relação com o recibo: estado persistido deliberadamente semeado para testar a defesa contra composição tardia. A recuperação retorna o recibo anterior e conserva exatamente a nova pendência. Reutilizar a chave de recuperação continua apenas recuperando; seleção alterada, usuário/conversa/rota estrangeiros são rejeitados. Só uma nova requisição normal, com nova chave e reserva vinculada à nova proposta, pode executar a segunda movimentação. Novas repetições não a duplicam.
- Resposta AI inesperada tentando devolver ActionRequest durante composição: não seleciona nem executa essa ação. A recuperação conserva o recibo e a proposta até receber composição válida. Chamadas explícitas ao seletor e executor com a entrada de recuperação também são bloqueadas.

Limpeza de estado consistente: se a proposta ainda corresponde à operação anterior, uma composição bem-sucedida a remove; se existe uma proposta posterior diferente, ela é preservada (`ConversationService.java:182` e comparação do snapshot no finalizador). A resposta de recuperação informa que a nova mensagem não foi processada, sem botões ou alegação de nova execução.

Evidências executáveis: `TransactionConfirmationEdgePostgresTest.java:94`, `:127`, `:152`.

## 2. Validade temporal — PROTEGIDO

`FinancialPreview.java:28` lê a data financeira de known_fields.resolved_date. A data atual em America/Sao_Paulo (`:29`) é somente uma condição de elegibilidade contra data futura: ela não substitui nem recalcula a data apresentada. A virada normal do dia torna a data anterior passada e não modifica os bytes do resumo.

`validPrompt()` reconstrói o resumo a partir do payload congelado e exige mensagem ASSISTANT da conversa com conteúdo idêntico e created_at maior que clock_timestamp() menos dez minutos (`:38`). O vencimento é uma comparação entre instantes PostgreSQL (TIMESTAMPTZ), não entre datas de calendário ou representações locais. Exatamente no limite a condição estrita não aceita a prévia; o teste integrado usa margens de nove e onze minutos, sem depender de temporizações de milissegundos.

Testes novos:

- Renderização imediatamente antes/depois da meia-noite UTC e da meia-noite de São Paulo, para despesa e receita: resumo integral idêntico, preservando o payload.
- AI nos mesmos dois limites e nos modos local/production-routing offline: data da prévia é a data de negócio original, e o ActionRequest confirmado conserva todos os campos exatamente.
- Execução PostgreSQL sob mudança controlada da data de negócio, para receita e despesa: valor, descrição, tipo, categoria e data gravados correspondem ao autorizado; a data original não é reancorada.
- Repetição pela mesma chave após expiração da prévia e avanço do calendário recupera o recibo já executado, sem uma segunda transação.
- Prévia expirada na virada do dia é rejeitada antes do AI, mantendo o JSON original da pendência e sem efeito financeiro.
- Sessões PostgreSQL em UTC e America/Sao_Paulo: a troca de timezone conserva o mesmo Instant de created_at; prévias de nove minutos continuam válidas e de onze minutos são rejeitadas.

Evidências: `FinancialPreviewMidnightTest.java:20`; `TransactionConfirmationEdgePostgresTest.java:170`, `:186`, `:209`; AI `tests/scenario/test_transaction_confirmation_midnight.py`.

Limites da simulação: relógio de domínio controlado em testes por Clock.fixed e mock estático de LocalDate.now(zone), sem alterar produção. O relógio do PostgreSQL e o do sistema operacional não foram alterados; o TTL foi exercitado contra clock_timestamp() real e timestamps relativos, com troca efetiva do timezone da sessão. Não é uma simulação física de todos os servidores atravessando simultaneamente a meia-noite. Essa separação verifica a data financeira congelada e a validade por instante independentemente. Recuo de relógio que torne uma data armazenada futura pode causar rejeição segura; não há conversão silenciosa da data. Não foi feita auditoria de sincronização NTP ou infraestrutura.

## Testes executados

Backend: `mvnw.cmd -q -Dtest=FinancialPreviewMidnightTest,TransactionConfirmationEdgePostgresTest,TransactionConfirmationPostgresTest,MessageIdempotencyPostgresTest,ActionExecutionPostgresTest test`.

| Classe | Aprovados | Falhas/erros/ignorados |
| --- | ---: | --- |
| FinancialPreviewMidnightTest | 4 | 0 |
| TransactionConfirmationEdgePostgresTest | 8 | 0 |
| TransactionConfirmationPostgresTest | 55 | 0 |
| MessageIdempotencyPostgresTest | 21 | 0 |
| ActionExecutionPostgresTest | 30 | 0 |
| Total | 118 | 0 |

AI: `python -m pytest -q tests/scenario/test_transaction_confirmation_midnight.py tests/scenario/test_transaction_preconfirmation.py --disable-warnings --tb=short` — 62 aprovados, nenhuma falha/erro. Oito ocorrências novas AI; 12 backend.

PostgreSQL 18 exclusivamente local/descartável, loopback 54837; dados sintéticos e AI mockado nos testes PostgreSQL. Nenhum provedor pago, serviço remoto ou migration remota. Não se trata de uma nova execução HTTP real entre os serviços; os cenários AI e persistência foram exercitados nos seus respectivos limites determinísticos.

Não houve falha adicional a comparar. Evidência ampla anterior das mesmas heads foi preservada: backend 471 aprovados/34 ignorados/zero falhas; base 410 aprovados/34 ignorados/zero falhas. AI 3631 aprovados/242 falhas/1 erro, contra base com 3576 aprovados/mesmas falhas e erro. Os conjuntos dos 243 IDs problemáticos foram novamente comparados e são idênticos. As suítes amplas não foram repetidas nesta investigação pontual, e a suíte ampla AI continua sem aprovação integral pelas ocorrências preexistentes.

## Arquivos locais acrescentados e impacto nas PRs

Backend:
- src/test/java/br/com/juntaai/service/FinancialPreviewMidnightTest.java
- src/test/java/br/com/juntaai/service/TransactionConfirmationEdgePostgresTest.java
- docs/transaction-preconfirmation-edge-validation.md (este relatório)

AI:
- tests/scenario/test_transaction_confirmation_midnight.py

Nenhum arquivo existente foi modificado. Logs ignorados e relatórios JUnit gerados são evidências locais, não candidatos a publicação. Os dois relatórios intermediários preexistentes no AI foram preservados.

Não é necessário corrigir produção nas PRs #55/#47 por estes cenários. A publicação dos testes complementares nas PRs existentes foi posteriormente autorizada pelo usuário. Continua obrigatória a ordem backend/V15 antes do AI, com revisão humana antes de merge. Nenhuma proteção foi contornada. A investigação original não realizou staging, commit ou push; a autorização posterior limita-se à publicação dos testes/documentação nas PRs existentes, sem nova PR, merge ou deploy.
