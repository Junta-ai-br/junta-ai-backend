# API — junta-ai-backend

> Status: **Implemented** para os endpoints abaixo. Documentação interativa
> completa (Swagger) disponível em `/swagger-ui.html` com o servidor rodando.

Base URL local: `http://localhost:8080`

## Autenticação

> Sem senha local. Duas formas de entrar: Google ou código de 6 dígitos
> por e-mail. Espelha `frontend-v2/src/components/Cadastro/Cadastro.jsx`
> e `frontend-v2/src/components/Login/LoginForm.jsx`.

| Método | Rota                      | Descrição                                          | Auth |
|--------|---------------------------|------------------------------------------------------|------|
| POST   | `/auth/register`          | Cadastro (nome, e-mail, WhatsApp + onboarding opcional) — já devolve tokens | não |
| POST   | `/auth/access-code/request` | Envia código de 6 dígitos para o e-mail             | não  |
| POST   | `/auth/access-code/verify`  | Confirma o código e devolve os tokens               | não  |
| POST   | `/auth/google`             | Troca o ID Token do Google pelos tokens da aplicação | não  |
| POST   | `/auth/refresh`            | Rotaciona o refresh token                            | não  |
| POST   | `/auth/logout`             | Revoga o refresh token informado                     | não  |

Login por código só funciona para quem já tem conta (`/auth/register`
primeiro). Login com Google cria a conta automaticamente no primeiro
acesso, se ainda não existir.

Todas as rotas abaixo exigem header `Authorization: Bearer <accessToken>`.

## Categorias
`GET|POST /categories`, `PUT|DELETE /categories/{id}`

## Transações
`GET|POST /transactions` (paginado, filtros `from`/`to` ISO-8601),
`PUT|DELETE /transactions/{id}`

## Metas
`GET|POST /goals`, `PUT|DELETE /goals/{id}`, `PATCH /goals/{id}/progress`

`POST`, `PATCH /{id}/progress` e `DELETE` exigem exatamente um
`Idempotency-Key` em formato UUID completo. Ausência, formato inválido,
valores separados por vírgula ou cabeçalhos repetidos retornam 400.
Repetir a mesma chave e os mesmos dados recupera o resultado original:
200 com `GoalResponse` em criação/aporte, 204 na exclusão, inclusive após
a meta ter sido excluída. Após exclusão, um recibo anterior de criação/aporte
é histórico; consulte `GET /goals` para o estado atual.

O namespace é `(usuário autenticado, chave)`. Operação, ID da meta e corpo
integram a identidade da solicitação: reutilizar a chave em outra operação,
meta ou corpo retorna 409 depois de um resultado persistido. Usuários
diferentes podem usar a mesma chave sem compartilhar recibos.
Efeito e recibo são atômicos; falhas com rollback não reservam a chave nem
persistem o resultado de erro. Uma nova tentativa após rollback será reavaliada.
A validação temporal do prazo ocorre no domínio apenas quando há nova gravação;
um replay não perde seu recibo porque o vencimento passou.

Persistir/reutilizar a identidade da tentativa no cliente é obrigatório para
deduplicação. Uma nova chave é outra operação, mesmo com o mesmo conteúdo.
No fechamento do MVP, o frontend preserva somente a chave opaca vinculada à
sessão em sessionStorage. Depois de reload, relê metas/histórico e bloqueia novos
envios até conferência e descarte explícito do aviso de resultado incerto.
Não há reenvio automático ou endpoint adicional de recuperação de recibos.

## Importação de simulação salva como meta — Incremento C

`POST /planner/simulations/{id}/goal`, autenticado, exige um único
`Idempotency-Key` UUID. Corpo: `{ "deadline": null, "confirmReimport": false }`.
Retorna 200 com GoalResponse após commit. Nome/alvo vêm da simulação do usuário;
progresso começa em zero. Meses e valor mensal simulados não viram prazo/aporte.
A simulação original permanece preservada.

V12 (`V12__planner_goal_imports.sql`) mantém vínculo único por simulação e depende
das tabelas existentes de metas/simulações e dos recibos V11. Mesmo comando recupera
o recibo; outra chave para simulação já vinculada retorna 409
`simulation_already_imported` no campo `mensagem`. Simulação inexistente/estrangeira
retorna 404. Após excluir a meta, o vínculo fica sem goal_id: nova importação exige
`confirmReimport: true`, senão retorna 409 `simulation_reimport_confirmation_required`.
Uma nova importação confirmada cria outro ID com progresso zero, sem restauração.
Prazo passado ou nome/valor fora dos limites do domínio de metas retorna 400.

Integrar backend/V12 antes do frontend. Não há dependência do AI Service ou de uma
branch do Incremento B. A interface solicita confirmação, deixa vencimento vazio,
bloqueia cliques concorrentes e reutiliza a chave em retry com resposta incerta.
Após reload, o vínculo persistente continua impedindo nova meta duplicada; o
Assistente consulta a listagem oficial ao abrir. Nenhuma gravação ocorre ao salvar
ou listar simulações.

## Dashboard
`GET /dashboard?from=YYYY-MM-DD&to=YYYY-MM-DD`

## Chat / IA
`GET|POST /conversations`, `GET /conversations/{id}/messages`,
`POST /conversations/{id}/messages` → dispara a chamada ao `junta-ai-ai`.

---

## Contrato Backend ↔ junta-ai-ai

> **Status: Implemented (espelhado)** — Este NÃO é mais uma proposta.
> É o contrato real, definido pelo repositório `junta-ai-ai`
> (`specs/001-ai-service-foundation/contracts/service-boundary.md` e
> `src/junta_ai/domain/models.py`), construído com Spec Kit pela equipe de
> IA. Qualquer mudança lá precisa ser espelhada nos records em
> `br.com.juntaai.integration.ai.model` deste repositório.

O Backend chama `POST {AI_SERVICE_BASE_URL}/v1/conversations/process`.

No Incremento A de metas, `financial_state.goal_progress` usa UUID da meta como
chave; cada valor contém `id`, `name`, `targetAmount`, `currentAmount` e `status`.
Os nomes não são chaves únicas. O AI Service usa o nome explícito para resolver
uma pergunta pendente; a autorização e a seleção final permanecem no backend.

### Visão geral do fluxo

O serviço de IA é **stateless**: não guarda nada entre chamadas, não
autentica usuários, não acessa banco. Cada rodada de conversa pode
envolver **uma ou duas chamadas** do Backend para a IA:

1. **Primeira chamada**: Backend envia a mensagem do usuário + contexto +
   o estado financeiro autorizado (saldo, transações recentes, metas) +
   o `pending_operation` da rodada anterior, se houver (guardado em
   `Conversation.pendingState`, serializado em JSON).
2. A IA responde com uma **decisão** (`decision.type`):
   - `clarification` / `confirmation` → falta informação; o Backend só
     mostra a mensagem e guarda o novo `pending_operation` para a próxima
     mensagem do usuário. Nada é executado.
   - `action_request` → a IA propõe uma operação estruturada
     (`action_request`, com `operation`, `payload`, `action_id`). O
     Backend **executa de fato** (via `TransactionService`/`GoalService`),
     monta uma `ExecutionConfirmation` (sucesso/falha/rejeitada/indisponível
     + estado autoritativo pós-operação) e faz uma **segunda chamada** à
     IA só para ela compor a frase final de resposta ao usuário.
   - `response` / `financial_query` → resposta direta, nada a executar.
3. A IA **nunca** escreve no banco, nunca afirma que uma operação foi
   concluída sem receber a confirmação do Backend, e nunca cita saldo/meta/
   transação que não veio no `financial_state` autorizado.

### Operações suportadas hoje pelo Backend (dentro de `action_request`)

| `operation`          | Comportamento                                             |
|-----------------------|------------------------------------------------------------|
| `expense` / `income`  | Cria uma `Transaction` (categoria resolvida pelo nome).    |
| `goal_contribution`   | Aplica aporte via `GoalService.addProgress`.                |
| `goal_withdrawal`     | Retira progresso via `GoalService.withdrawProgress`, sem transação financeira. |
| `correction` | Corrige valor e/ou categoria de uma transação do usuário após proposta e confirmação explícita. |
| `category_update`, `recurring_confirmation` | Ainda não implementadas — o Backend responde `unavailable`, nunca finge sucesso. |

### Contrato de dados (nomes de campo em snake_case, iguais ao Pydantic)

Correção conversacional reutiliza `ActionRequest`, `PendingOperation` e os recibos
existentes. O payload acrescenta `correction_target: {id, updated_at}` opcional
(nulo nas outras operações), apontando para a revisão proposta da transação.
`transaction_type` continua sendo `income` ou `expense`, o tipo original.
Valor e categoria no payload são os valores finais explicitamente propostos.
O executor exige mensagem afirmativa e payload igual à proposta persistida
(`pending_correction`, operação `correction`, campo pendente `confirmation`).

O snapshot autenticado acrescenta `id`, `updated_at` e `created_at` aos registros,
`current_state.last_transaction_id` quando não há empate na última criação, e
`correction_records_complete`. Mantém a lista recente limitada a dez registros
dos últimos 90 dias e inclui a última criação quando não estiver nessa lista.
Uma lista parcial exige escolha explícita do registro; não prova unicidade.

O backend bloqueia a linha, valida propriedade, tipo, revisão e categoria, e
reutiliza `TransactionService.update`, preservando ID, tipo, descrição e data.
Atualizações pela interface também obtêm o bloqueio da linha. Revisão divergente,
registro removido ou de outro usuário produzem recibo rejeitado sem alteração.
Repetição da mesma mensagem/ação recupera os recibos existentes.
Uma rejeição confirmada de correção encerra sua proposta para exigir nova identificação.
Não há endpoint, migration, tabela ou alteração no MCP experimental.
Integrar backend antes do AI Service. O frontend reutiliza a recarga financeira do chat.

A correção P0 de aportes exige confirmação explícita antes de executar
`goal_contribution`. O AI Service resolve uma única meta do snapshot autenticado
e devolve uma proposta com `state=pending_goal`,
`unresolved_fields=["confirmation"]`, `known_fields` contendo o payload
completo e `target_reference` contendo o UUID da meta. São campos já existentes;
não há novo endpoint, migration ou alteração de schema.

São aceitas somente `sim`, `confirmo`, `pode confirmar` e `sim, pode confirmar`.
AI Service e Backend colapsam espaços ASCII (incluindo tabulação e CR/LF) e NBSP,
removem espaços externos, convertem para minúsculas e retiram pontos/exclamações
finais. Não removem acentos ou palavras: `pode  confirmar` é válido; `SÍM`,
negativas, perguntas, cancelamentos e novas instruções não autorizam o aporte.
Uma revisão de valor/meta substitui a proposta e exige nova confirmação; alvo
inexistente ou ambíguo exige esclarecimento, sem reutilizar o alvo anterior.

O Backend exige uma resposta afirmativa inequívoca correspondente à proposta
persistida, payload idêntico e `temporal_status=realized`. Revalida sob bloqueio
o nome exato e único no escopo do usuário e o UUID proposto. Ausência ou divergência
de consentimento/identidade produz REJECTED com
`goal_contribution_confirmation_required`. Falhas da consulta continuam sendo
falhas de execução, sem recibo falso de sucesso. Permanecem os limites de valor,
a elegibilidade da meta, o ledger e a idempotência existentes. O aporte continua
alterando somente o progresso da meta, sem criar despesa.

No Incremento B, `goal_withdrawal` reutiliza `monetary_value` positivo,
`goal_reference` e `transaction_type=goal_withdrawal`, sem categoria. A meta deve
ter uma única correspondência do usuário. A retirada não supera o acumulado,
usa o mesmo bloqueio/recibo do executor e pode reabrir uma meta COMPLETED.
Excesso de retirada produz recibo REJECTED com `goal_insufficient_progress`.
Nenhum endpoint HTTP, tabela ou migration foi adicionado. Integrar backend antes
do AI Service que emite a nova operação; o frontend mantém sua recarga existente.

Ver `br.com.juntaai.integration.ai.model` — cada classe tem um comentário
apontando para o arquivo Python equivalente em `junta-ai-ai`. Os
principais:

- `AIServiceRequest` — corpo enviado pelo Backend.
- `ProcessingResult` — corpo devolvido pela IA.
- `PendingOperation`, `ActionRequest`, `ExecutionConfirmation`,
  `FinancialStateSnapshot` — as peças de estado trocadas entre as duas
  chamadas de uma mesma rodada.

### Timeout e fallback

`ai.service.connect-timeout-ms` (padrão 3s) e `ai.service.read-timeout-ms`
(padrão 15s). Se o serviço de IA não responder dentro desses limites ou
estiver fora do ar, o Backend responde `503 Service Unavailable` com uma
mensagem amigável — nunca deixa a requisição do usuário pendurada.

### Incremento I3 local — execução durável e composição separada (revisão pendente)

O backend passa a reservar action_id único e registrar ação/resultado associado
ao usuário/conversa/mensagem, atomicamente com o efeito financeiro. Segunda chamada
AI ocorre depois do commit. Repetição correspondente recupera recibo; conteúdo ou
associação divergente é rejeitado. DTOs/endpoints acima permanecem os mesmos.

Após execução commitada, indisponibilidade/composição inválida da IA retorna uma
resposta backend segura com actionExecuted e composição pendente, sem rollback ou
reexecução. O próximo POST de mensagem retoma o recibo antes de interpretar novo
texto financeiro. Na recuperação, actionExecuted=false: a entrada atual não foi
processada, e o aviso backend explica que o resultado pertence à operação anterior.
Não há execução automática nem fila/promessa de processamento posterior; a nova
intenção precisa ser reenviada quando a recuperação terminar. No turno originário,
actionExecuted=true continua significando efeito financeiro confirmado, mesmo se
a composição falhar. O recibo originário continua preservado.

A finalização usa lock transacional curto de conversa/recibo e associação interna
composition_message_id (V5). Uma única resposta final é persistida por recibo;
chamadas tardias reutilizam a resposta aceita, inclusive após timeout ou alteração
de pendência. Chamadas AI concorrentes não são deduplicadas. A composição aceita
é persistida sem o aviso contextual da entrada nova, independentemente do vencedor.
O turno originário recebe esse resultado sem aviso de entrada não processada e
mantém actionExecuted=true quando o efeito financeiro foi confirmado. Toda chamada
usada apenas para recuperação recebe actionExecuted=false e o aviso no conteúdo
do MessageResponse HTTP. Esse conteúdo inclui um prefixo backend que não faz
parte da mensagem armazenada: id, role e createdAt são os mesmos, e GET messages
retorna o conteúdo persistido, sem o prefixo contextual. Acrescentar o aviso não
grava mensagem nem finaliza novamente o recibo; não há inspeção/remoção de prefixos
ou comparação de texto AI para determinar o contexto da chamada.
Falhas de composição podem continuar registrando fallbacks no histórico; esses
fallbacks não são finalizações. Não há reserva remota ou lock durante chamadas AI.
Falha da primeira chamada ainda retorna 503. Pendência não é apagada por response
sem substituição explícita ou por erro de composição.

I3 permanece parcial: consentimento autenticado e retomada IA de recibo expirado
continuam pendentes. A identidade durável de entrada é adicionada pela I3.1 abaixo. A proteção
execution_confirmation_stale permanece. Recorrência/correção/categoria seguem
indisponíveis. Evidências, limites e decisões em [i3-audit.md](i3-audit.md).

### I3.1 — identidade durável do envio de mensagens

O POST `/conversations/{id}/messages` exige `Idempotency-Key`, um único UUID v4
canônico de 36 caracteres. O corpo continua `{"message":"..."}` e a resposta de
sucesso continua `ChatReplyResponse`. O contrato Backend↔AI não mudou.
**Quebra de compatibilidade:** clientes antigos sem header recebem 400 antes de
qualquer admissão ou chamada IA. O frontend ainda precisa ser integrado/atualizado;
esta implementação é exclusivamente backend.

O cliente gera a chave uma vez por envio e reutiliza a mesma chave, conversa e
mensagem quando a resposta é perdida ou o estado é incerto. Outro envio intencional
usa outra chave, mesmo com texto igual. Não gerar chave nova automaticamente após
timeout. A chave não autentica: JWT e ownership continuam obrigatórios.

O namespace é `(usuário autenticado, chave)`. Reutilização em outra conversa do
mesmo usuário ou com texto diferente gera conflito. Outro usuário pode usar o
mesmo UUID em seu próprio namespace, sem acesso à entrada anterior. O texto é
comparado exatamente após deserialização/validação, sem normalização adicional;
espaços de formatação do JSON não importam, espaços dentro de `message` importam.

| Situação | HTTP / `code` |
|---|---|
| Header ausente | 400 / `idempotency_key_required` |
| UUID não v4, valor vazio ou múltiplos valores | 400 / `idempotency_key_invalid` |
| Chave com outro conteúdo/conversa | 409 / `idempotency_key_conflict` |
| Primeira interpretação ainda em andamento | 409 / `message_processing_in_progress`, `Retry-After: 2` |
| Falha inicial, prazo vencido sem seleção ou resposta inicial inválida | 503 / `message_processing_failed` |
| Resultado concluído ou efeito confirmado com fallback de composição | 200 / corpo existente |

Erros específicos preservam o envelope existente (`timestamp`, `status`, `erro`,
`mensagem`) e acrescentam `code`. Sem chave/texto/payload nos erros. CORS permite
`Idempotency-Key` e expõe `Retry-After`. Autenticação/ownership mantêm 401/404.

#### Persistência e transações

V6 cria `conversation_message_entries`. Unicidade definitiva por usuário/chave,
mensagem original única, ação selecionada única e FKs de ownership/associação.
A admissão reserva a chave e grava a mensagem USER no mesmo commit, antes da IA.
Somente o criador interpreta. Uma ação completa selecionada é persistida antes de
entrar no executor; retries retomam esse conteúdo, sem reconstrução/UUID/prazo novos.
Na mesma transação do efeito e recibo, a entrada é vinculada ao ledger e a cópia
temporária do JSON selecionado é removida. O ledger conserva a ação completa.

Estados: `PROCESSING` → `ACTION_SELECTED` → `RECEIPT_PENDING` → `COMPLETED`;
respostas sem ação passam de `PROCESSING` a `COMPLETED`; falhas iniciais passam
somente de `PROCESSING` a `FAILED`. Ordem de locks: conversa pertencente ao
principal → entrada, quando houver → ledger. Nenhuma chamada IA sob esses locks.

`AI_MESSAGE_PROCESSING_WINDOW_MS` configura a janela inicial (default 60000 ms).
O startup exige que seja no mínimo `AI_CONNECT_TIMEOUT_MS + AI_READ_TIMEOUT_MS +
5000 ms` (defaults HTTP: 3000/15000 ms). O provider AI consultado configura 10000 ms
por tentativa e duas retentativas; a janela não amplia o timeout HTTP do cliente
backend. Se aumentar os timeouts HTTP, ajuste também a janela. PostgreSQL mede o
prazo com `clock_timestamp()` sob lock. Não há job de expiração: callbacks/retries
verificam o prazo. Uma entrada já selecionada/commitada nunca vira falha inicial.

Crash antes da seleção: retry encontra processamento em andamento até o prazo;
depois recebe falha terminal sem nova interpretação. Um novo envio deliberado é
permitido após essa falha terminal, com outra chave. Crash/rollback após seleção:
retry usa a mesma ação e continua sujeito ao `expires_at` original. Crash após
commit: recupera o recibo, sem repetir finanças. Falha de composição: mantém
fallback e permite repetir somente composição; o resultado pode evoluir de
fallback para resposta final, preservando entrada/ação/efeito.

Uma entrada admitida durante recibo pendente conserva `RECOVERY_ONLY` e a referência
à operação anterior. Seu texto jamais é interpretado/executado, inclusive após
restart ou conclusão do recibo. Mantém `actionExecuted=false` e aviso somente HTTP.
Retry do originário conserva `true` quando o efeito daquele envio foi confirmado;
isso não significa efeito novo. V5 continua aceitando uma composição final única.

#### Retenção e limites

Nenhuma chave expira ou é purgada automaticamente. Não existem endpoints atuais
de exclusão de usuário, conversa ou mensagem. As exclusões atuais de transação,
meta e categoria não apagam entrada/ledger; referência de efeito no ledger não é
FK com cascade. As FKs V6 impedem apagar vínculos protegidos via cascades existentes.
Não foi criada funcionalidade de exclusão/tombstone/governança. Qualquer futura
exclusão/purga desses registros exige desenho explícito contra replay antes de
ser habilitada. Não reutilizar chaves removidas manualmente do banco.

A identidade não deduplica intenções com chaves distintas, não autentica S2S e
não implementa consentimento, recuperação IA de recibo expirado, MCP ou frontend.
Recorrência/correção/categoria continuam indisponíveis. I3 permanece parcial.

## Limites e respostas de proteção (revisão de segurança/escala)

| Situação | Resposta |
|---|---|
| Muitas requisições de um IP em `/auth/*` | `429` + `Retry-After` |
| Código de e-mail errado 5 vezes / pedidos de código acima do teto | `429` |
| Mais de 20 mensagens/min de um usuário no chat | `429` + `Retry-After` |
| Corpo da requisição acima de 64 KB (`MAX_REQUEST_BYTES`) | `413` |
| `POST /auth/access-code/request` com e-mail sem conta | `202` igual ao de e-mail com conta (nada é enviado) |
| Apagar categoria que tem transações | `409` |
| Mais de 200 simulações salvas no Planejador | `409` |
| Listas (transações, conversas, mensagens) | tamanho de página máx. 100 (mensagens: 200 mais recentes) |
