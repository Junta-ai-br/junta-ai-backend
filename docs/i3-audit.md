# I3 parcial — evidências de execução no backend

**Implementado localmente / revisão pendente. I3 não completo.** 10/10/2026.
Checkout: junta-ai-backend, branch chore/backend-smtp-compose, HEAD
59687335d26baf25d0d158a4f303380635aea125. Branch mantida; README/compose locais
preservados. AI Service feat/update-mcp-contract/a09c652, I1/I2 locais preservados.
Contrato MCP 0.3.0-proposal/conteúdo 0.2.0-proposal, políticas, US2 e produção
continuam não aprovados. O servidor MCP não recebe nenhum registro deste ledger.

## Inspeção e desenho

Reconfirmado no checkout atual: sendMessage transacional abrangia efeito financeiro
e segunda chamada IA; o retorno padrão apagava pendência. Não havia neste caminho
registro idempotente. Isso não afirma ausência de controles em todo o backend.
Lidos controller/JWT/principal, ConversationService, AiClient/DTOs, serviços e
repositories financeiros, entidades, migrations V1–V3, testes, pom, README/docs,
Constituição, SEC-02 (Em Validação técnica), desenho e audit I2 do AI Service.
AGENTS.md backend não encontrado; instruções de segurança da tarefa preservadas.

As fronteiras novas são beans Spring separados, não self-invocation:

1. ConversationTurnStore.begin: transação curta; ownership pelo principal recebido
   do controller, mensagem aceita e snapshot/contexto/pendência. Commit antes da IA.
2. ConversationService: orquestração sem transação financeira abrangente. Confere
   request_id/contract_version do resultado contra a chamada emitida pelo backend.
3. ActionExecutionService.execute: proxy REQUIRES_NEW. Bloqueia conversa pertencente
   ao usuário; reserva action_id único via INSERT ON CONFLICT; bloqueia registro;
   confere usuário/conversa e JSON canônico completo do DTO ActionRequest. Repetição
   correspondente recupera o recibo; divergência não executa. Novo efeito exige
   pendência ainda correspondente e ausência de outro recibo a compor.
4. Serviços financeiros existentes participam da mesma transação de execução.
   Efeito, referência de transação/meta e recibo são gravados atomicamente. Uma
   falha escapa e provoca rollback. Trigger PostgreSQL diferido impede commit de
   reserva sem recibo terminal. A IA nunca é chamada dentro dessa transação.
5. Somente após retorno do proxy (commit) é enviada a confirmação persistida à IA.
   Composição/armazenamento da resposta usam outra transação curta. Falha/timeout
   IA não desfaz efeito; recibo permanece com composition_pending=true.

Status SUCCESSFUL é provisório dentro da transação; não é publicado antes do
commit. created_at/updated_at são timestamps internos, não instante comprovado
de commit nem prazo de consentimento. Snapshot autoritativo registra o estado
observado na transação financeira, não garantia de atualidade eterna.

## Associação, controles e retomada

Origem do usuário: JWT validado → AuthenticatedUser → controller. IDs de AI não
autorizam. Conversation ownership é rechecado; categoria/meta são resolvidas
somente entre alvos daquele usuário, sem escolher arbitrariamente nomes ambíguos.
Foreign keys compostas vinculam usuário/conversa/mensagem. action_id é único no
banco; JSON do DTO inclui operação, todos os campos financeiros e ciclo de vida.
Comparação é do DTO tipado Jackson, não autenticação ou igualdade dos bytes HTTP.
A leitura do recibo confere status/ação/operação/estado contra o registro antes
de publicar; inconsistência não gera nova execução. request_id/mensagem originários são mantidos; callback pode ter novo request_id.

Entrada financeira deste caminho aplica os limites já existentes do banco
NUMERIC(19,2), valor positivo e validação de TransactionRequest. Valores que seriam
arredondados/overflow são rejeitados; zeros decimais adicionais não mudam valor.
Data fornecida inválida não é substituída por hoje; data futura segue PastOrPresent
do DTO. Data ausente mantém o default backend existente. transaction_type informado
deve corresponder à operação. Isto não completa elegibilidade temporal: o flag
temporal isolado ainda não define planejamento/realização em todos os contratos.
Não foram modificados os outros endpoints financeiros para corrigir suas lacunas.

Novo execute rejeita expires_at passado quando presente. Repetição de execução
já registrada recupera o mesmo recibo, sem aceitar outra execução pelo prazo atual.
Não foi criado prazo novo para null, consentimento ou revogação.

Retomada: POST de mensagem existente consulta recibo pendente antes da primeira
interpretação. Envia ação/recibo/originalMessage persistidos apenas para composição;
o novo texto não inicia outra ação enquanto essa composição está pendente.
actionExecuted=true nessa retomada significa execução anterior comprovada, não
efeito novo desse POST. GET messages existente conserva mensagens/fallback backend.
Resposta final com outra ação, envelope errado, mensagem vazia ou reason inesperado
não é usada como confirmação. Default response sem nova pendência preserva estado;
cancelamento explícito e conclusão correspondente podem limpar a mesma pendência.
Mudança concorrente de pendência não é sobrescrita pela resposta antiga.

Após falha de composição de execução commitada, o backend informa o fato financeiro
do próprio registro e distingue resposta IA pendente. A operação não é repetida.
Execução indeterminada/rollback não é anunciada como sucesso; erros financeiros
inesperados são seguros. AiClient não registra payload/exception message bruta.

## Limites contratuais e itens bloqueados

DTOs/endpoints públicos inalterados. Após commit seguido de falha IA, o POST agora
retorna resposta backend válida com actionExecuted e composição pendente, em vez
de lançar erro que reverteria a execução. Falha na primeira chamada continua 503;
a mensagem já aceita pode permanecer no histórico. Sem nova API de recibos.

- Idempotência cobre o mesmo action_id/conteúdo/associação no ledger. IDs diferentes
  podem produzir operações iguais legítimas; não há deduplicação lógica de entrada.
  ChatMessageRequest contém só message; retry HTTP sem chave estável de turno não
  é comprovadamente a mesma intenção. Message.id interno não resolve esse retry.
- Recibo tardio ainda pode receber execution_confirmation_stale no AI Service.
  Nenhuma verificação foi removida. Backend mantém o resultado durável e deixa
  composição pendente; retomada IA após expiração continua bloqueada contratualmente.
  Proposta mínima, não implementada: referência verificável de recibo/aceitação
  dentro do prazo e de commit, sob S2S revisado. Não inferir isso de timestamps.
- Não há captura autenticada de consentimento vinculada à proposta/revisão nem
  controle de revogação/reutilização de consentimento. Recibo não é consentimento.
  Recorrência/correção/categoria continuam UNAVAILABLE; nenhum “sim”, booleano ou
  afirmação LLM os habilita. Capacidade/prazos/governança continuam por definir.
- Sem S2S implementado, prova criptográfica, replay durável de entrada ou políticas
  aprovadas. Resolução de nomes não substitui identidade de alvos fornecida pelo
  backend; alvos ambíguos são rejeitados. Concorrência com outros escritores REST
  da meta/pendência e sua revisão global permanece avaliação própria.
- FK CASCADE segue lifecycle de exclusão do domínio; não é tombstone anti-replay
  após remoção autorizada nem política de retenção aprovada. Retenção/governança
  dos registros internos financeiros precisam de revisão.
- Snapshot, commit e restrições foram testados em PostgreSQL real; isso não prova
  deployment, falha de energia, backend/AI remotos ou conformidade OWASP completa.

## Validação nova desta etapa

Cópia isolada no workspace, JDK 21.0.12, Maven 3.9.16 do cache do wrapper;
PostgreSQL **17.11 descartável**, em 127.0.0.1:15483, banco
junta_i3_disposable_20261010. Não usado banco padrão/compartilhado/produtivo.
Cluster próprio, sem alterar serviços do Windows ou compose. Migrações V1–V4
executadas e Hibernate validate passou. Sem H2, Testcontainers ou dependências novas.

Maven verify completo passou com 134 testes na cópia e inicialmente no checkout.
Após a defesa final de consistência de recibo, o checkout passou com **135 testes,
zero falhas/erros/skips** (20,251 s somados nos reports finais);
17 casos novos usam PostgreSQL/Spring reais; AI e falha de snapshot são fakes.
JaCoCo final no checkout: linhas **84,35%**, instruções **84,73%**, branches
**73,64%**. Maven verify concluiu compilação/testes/relatório/empacotamento.
Os testes exercitam repetição/conflito, concorrência, metas e isolamento,
rollback após escrita, constraint de reserva incompleta, commit visto em outra
conexão antes de callback fora de transação, timeout, recuperação sem reexecução,
estado pendente/obsoleto, consistência do recibo persistido e limites de expiração. Não apenas existência de classes.

AI Service: **110 passed, dois warnings, 1,17 s** nesta etapa, para testes de
confirmação, guardrails, contrato HTTP e I1/I2. Seus 202/498 anteriores permanecem
evidência I2, não execuções I3. Nenhum código AI alterado.

Rodadas intermediárias: um re-stub Mockito invocou resposta anterior com null;
corrigido no teste. Ao adicionar prioridade do recibo, o teste de duas rejeições
independentes precisava concluir a primeira composição; passou a fazê-lo pelo
fluxo real. Não afrouxados validators/constraints. Maven offline passou testes,
mas verify precisava de plugins de jar; baixados por Maven, sem alterar pom/lock.
Wrapper PowerShell encontrou problema de Target[0] no ambiente; usado exatamente
o Maven cached da distribuição configurada, sem mudar o wrapper. Avisos: Flyway
atual só declara suporte até PG16 (PG17.11 foi testado, não formalmente homologado),
Hibernate dialect e Mockito/Java agent/CDS. Aprovação de release permanece pendente.

## Arquivos e revisão

- V4/action_executions, entidade/repository: evidência privada, unicidade,
  associação e impedimento de reserva incompleta commitada.
- ActionExecutionService: transação financeira idempotente e recuperação do recibo.
- ConversationTurnStore/ConversationRepository: transações curtas e locks ownership.
- ConversationService: IA fora da transação financeira, correlação e retomada.
- AiClient: diagnóstico seguro.
- testes unitários/HTTP atualizados e ActionExecutionPostgresTest novo: fakes
  passam IDs realmente enviados; proxies/ACID/concorrência não dependem de mocks.
- docs/api.md/este audit e referências na spec 002 AI: impacto e pendências.

Diff exclusivo contra estado imediatamente anterior: I3-backend-review.diff,
separado dos documentos AI e dos diffs I1/I2 preexistentes. Sem staging, commit,
push, merge ou alteração GitHub. Revisão humana, consentimento, retomada expirada,
idempotência de entrada, S2S/governança/US2 e produção permanecem pendentes.

Comandos finais (JDK 21 e PostgreSQL descartável configurados apenas no processo):

```text
mvn -o -q -Dmaven.repo.local=<cache existente> -Dlogging.level.root=WARN -Ddebug=false verify
python -B -m pytest -q tests/integration/test_execution_confirmation.py tests/guardrails/test_confirmation_guardrails.py tests/contract/test_service_boundary.py tests/contract/test_i2_delivery.py tests/unit/test_i1_correlation.py
```

O binário mvn usado é 3.9.16, exatamente a distribuição do mvnw.cmd. Compilação
foi feita primeiro na cópia para tornar o diff concreto antes da escrita fora do
sandbox. Aplicação dos 13 arquivos conferiu hashes, branch/HEAD e paths; README,
compose e índices foram preservados. Nenhum arquivo/configuração Git foi escrito.
