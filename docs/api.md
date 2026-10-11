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
| `category_update`, `correction`, `recurring_confirmation` | Ainda não implementadas — o Backend responde `unavailable`, nunca finge sucesso. |

### Contrato de dados (nomes de campo em snake_case, iguais ao Pydantic)

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

I3 permanece parcial: não há idempotência lógica de mensagens com action_ids
diferentes, consentimento autenticado ou retomada IA de recibo expirado. A proteção
execution_confirmation_stale permanece. Recorrência/correção/categoria seguem
indisponíveis. Evidências, limites e decisões em [i3-audit.md](i3-audit.md).
