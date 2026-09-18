# Arquitetura — junta-ai-backend

> Status deste documento: **Implemented** (reflete o código deste repositório).

## Papel no sistema

O Spring Boot é a **autoridade da aplicação** (regra definida no documento
`junta-ai-estrutura-repositorios-e-banco.pdf`): concentra autenticação,
autorização, regras de negócio e persistência. Nem o frontend nem o
serviço de IA acessam o PostgreSQL diretamente.

```
Frontend (React) → Spring Boot (REST API) → PostgreSQL
                        │
                        └─→ junta-ai-ai (CrewAI + MCP) → LLM
```

## Camadas (Clean Architecture "prática")

```
controller/     → HTTP: request/response, validação de entrada (@Valid), status codes
service/        → regras de negócio, orquestração, transações (@Transactional)
repository/     → acesso a dados (Spring Data JPA)
entity/         → modelo de domínio persistente
dto/            → contratos de entrada/saída da API (nunca expomos entidades JPA)
integration/ai/ → porta de saída para o serviço de IA (única classe que conhece o junta-ai-ai)
security/       → JWT, filtro de autenticação, UserDetails customizado
exception/      → hierarquia de erros de negócio + handler global
config/         → beans de infraestrutura (OpenAPI, RestClient)
```

Cada controller depende só de services. Cada service depende só de
repositories e de outros services (nunca o contrário). Isso permite trocar
a camada de persistência ou o cliente HTTP da IA sem tocar em regra de
negócio.

## Autorização por propriedade do recurso

Todas as consultas de Category, Transaction, Goal e Conversation são
filtradas por `user_id` na própria query (`findByIdAndUserId`). Um usuário
nunca consegue, nem por engano, carregar o recurso de outro usuário — a
consulta simplesmente não encontra nada e o service lança
`ResourceNotFoundException` (404), sem revelar se o recurso existe.

## Fluxo de chat com IA

O contrato é definido pelo repositório `junta-ai-ai`
(`specs/001-ai-service-foundation/contracts/service-boundary.md`), não
por nós — o Backend só o espelha. Resumo:

1. `ConversationController` recebe a mensagem do usuário autenticado.
2. `ConversationService` persiste a mensagem, monta o "estado financeiro
   autorizado" (saldo, transações recentes, metas — via `DashboardService`
   e os repositórios) e o `pending_operation` da rodada anterior (guardado
   em `Conversation.pendingState`, já que a IA é *stateless*), e chama
   `AiClient.process(...)` — `POST /v1/conversations/process`.
3. A IA devolve uma **decisão**: `clarification`/`confirmation` (falta
   informação — o Backend só mostra a mensagem e guarda o novo estado
   pendente), `action_request` (propõe uma operação estruturada) ou
   `response`/`financial_query` (resposta direta).
4. Para `action_request`, o Backend **executa de fato** via
   `TransactionService`/`GoalService`, monta uma confirmação de execução
   (sucesso, falha, rejeitada ou indisponível — nunca finge sucesso) e faz
   uma **segunda chamada** à IA só para compor a frase final. A IA nunca
   escreve no banco nem afirma algo que o Backend não confirmou.
5. `AiClient` é a única classe que sabe o endereço do `junta-ai-ai`. Se o
   serviço estiver fora do ar ou expirar o timeout, lança
   `AiServiceUnavailableException` (503) — nunca deixa a requisição
   travada.

Ver `docs/api.md` para o contrato de integração completo, campo a campo.

## Decisões e trade-offs

- **Refresh token opaco (não é JWT)**: fica salvo em `refresh_tokens`,
  permitindo revogação real em logout/rotação. Um JWT de refresh não pode
  ser revogado sem uma blacklist — preferimos o registro em banco.
- **DTOs como Java records**: imutáveis, sem boilerplate, validação Bean
  Validation funciona nos parâmetros do record.
- **`AuthenticatedUser` customizado**: carrega o `UUID` do usuário no
  próprio principal do Spring Security, evitando um `SELECT` extra em toda
  requisição só para descobrir quem está logado.
