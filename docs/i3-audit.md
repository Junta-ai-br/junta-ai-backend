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

## 11/10/2026 — correção dos três achados da revisão I3

**Implementado localmente / revisão pendente; I3 permanece parcial.** Os registros
acima e suas contagens descrevem a etapa anterior, não esta execução.
Base imediatamente anterior: feat/i3-backend-checkpoint,
0ce87f54bc5be776c8d3ef62c465a23c660f4562; índice vazio; README.md e compose.yaml
modificados preexistentes, preservados byte a byte. AI: feat/update-mcp-contract,
426ae088b11336063a2a0fd1af8c50cb8809c3e5; índice vazio; cinco diffs locais
de revisão preservados. Não houve mudança posterior de HEAD detectada.

1. Antes, nova entrada recebia actionExecuted=true da operação anterior sem aviso.
   Agora, entrada aceita durante recibo pendente continua no histórico, não é
   interpretada/executada, retorna actionExecuted=false e aviso determinístico:
   nova mensagem não processada, resultado da operação anterior, reenvio necessário.
   O turno originário mantém sua semântica de efeito confirmado. Sem endpoint ou
   campo público novo; sem classificação LLM de "retomar" ou processamento futuro.
2. Antes, repetição de action_id não comparava user_message_id. Agora exige mesma
   mensagem, usuário, conversa e JSON completo; outra mensagem gera action_conflict.
   request_id de callback pode diferir, request_id/mensagem de origem não mudam.
   Recuperação de composição usa pendingReceipt e o registro original, não execute.
3. Antes, duas composições finalizavam o mesmo recibo e persistiam duas respostas.
   Agora, finish bloqueia conversa e recibo na mesma ordem de execute, confere
   associação e estado de finalização antes de qualquer alteração. Grava resposta,
   composition_message_id e composition_pending=false atomicamente. O perdedor
   reutiliza a resposta aceita, mesmo quando sua AI falha. Uma resposta antiga não
   limpa pendência de outro recibo nem pendência cujo estado mudou. IA fica fora
   dessa transação; chamadas AI duplicadas são possíveis. Não há lease/reserva
   permanente: rollback deixa o recibo pendente e permite nova tentativa.

V5 acrescenta referência privada e FK composta à mensagem final, sem mudar V4.
Recibos concluídos antes de V5 não são inferidos/backfilled por heurística; não são
selecionados por pendingReceipt. Caso interno inconsistente (recibo concluído sem
mensagem final) falha fechado com composition_evidence_invalid.
A implementação inicial desta retomada persistia o aviso quando a recuperação
vencia. Isso contaminava a resposta do turno originário tardio; a corrida inversa
foi corrigida na seção abaixo. Comportamento vigente: composição/fallback sem
aviso contextual persistido; aviso somente no MessageResponse HTTP de cada chamada
de recuperação, independentemente do vencedor. Mesmo id/timestamp, conteúdo HTTP
contextual distinto do GET messages. Mensagens de usuário e fallbacks preservados;
sem garantia de uma única mensagem de fallback em tentativas que continuam falhando.

Constituição I/III/IV/V/IX/X/XI, SEC-02 (Em Validação técnica) e referências OWASP
já adotadas foram consultadas: ownership pelo principal, autorização por recurso,
menor privilégio, integridade, dados não promovidos a autoridade e validação fora
do modelo permanecem. Não se declara auditoria OWASP integral ou conformidade.
Sem consentimento, retomada expirada, deduplicação entre action_ids, S2S, adapter,
MCP runtime, US2, novas operações, políticas, governança ou produção aprovados.
Regra de expiração, atomicidade financeira/recibo e guardrails I1/I2 preservados.

### Verificação desta retomada

Execução em cópia isolada do estado imediatamente anterior, JDK 21.0.12 / Maven
3.9.16 do wrapper/cache existente, PostgreSQL 17.11 descartável, cluster próprio
em .i3-resumption/pgdata, 127.0.0.1:15484/junta_i3_disposable_20261011. Nenhum
banco padrão, serviço Windows, compose ou banco compartilhado/produtivo usado.
Migrações V1–V5 e Hibernate validate exercitados por Spring real.

Maven verify completo passou: **145 testes, zero falhas/erros/skips**,
incluindo 27 casos PostgreSQL/Spring reais (10 novos nesta retomada).
JaCoCo: linhas 84.64%, instruções 85.04%,
branches 75.28%. AI: **110 passed, 2 warnings, 1,91 s**,
regressão de recibos/guardrails/contratos/I1/I2 executada novamente em Python 3.12.
Os 135/110 anteriores permanecem evidências históricas, não resultados reutilizados.
Comando backend: Maven 3.9.16, -o -q -Dmaven.repo.local=<cache existente>
-Dlogging.level.root=WARN -Ddebug=false verify; I3_DISPOSABLE_DB=true e DB_URL
exclusivamente do cluster acima. Comando AI: python -B -m pytest -q
-p no:cacheprovider tests/integration/test_execution_confirmation.py
tests/guardrails/test_confirmation_guardrails.py tests/contract/test_service_boundary.py
tests/contract/test_i2_delivery.py tests/unit/test_i1_correlation.py.


Cobertura nova observável: R$20 commitados + timeout + nova mensagem R$80 com
sucesso/falha; conflito entre mensagens originárias; corrida de retomadas com
barreira; callback tardio de sucesso/timeout após vencedor e após novo recibo;
mudança de pendência antes da primeira finalização; rollback/retry sem reserva;
isolamento; recuperação tardia quando o turno originário vence. AI é mock apenas
de transporte/falhas; persistência, locks, proxies e commit são reais. Os fakes
SMTP herdados impedem envio externo. Efeito, recibo, origem e histórico verificados.

Rodadas intermediárias: acesso inicial ao cache/uv bloqueado pelo sandbox, testes
executados com aprovação; campo do request no teste corrigido para user_message.
Regressão detectou aviso aplicado ao turno originário: restringido à recuperação
para preservar a resposta existente, sem enfraquecer a asserção de regressão.
Avisos existentes: Flyway só declara suporte até PG16; Java agent/CDS/dialect;
Starlette/httpx e AnyIO. Não modificadas dependências para ocultar avisos.

Diff incremental novo: revisao/I3-findings-backend.diff no AI Service, contra
snapshot imediatamente anterior desta retomada, excluindo README/compose locais.
Referências AI: revisao/I3-findings-ai-docs.diff. Diffs anteriores preservados.
Sem staging/commit/push/merge/PR/deploy ou alteração no GitHub.

## 11/10/2026 — ajuste exclusivo da corrida inversa de composição

**Implementado localmente / revisão humana pendente. I3 permanece parcial;
produção sem aprovação.** Base imediatamente anterior inclui todas as correções
locais da seção anterior, README/compose e V5 não rastreada. HEAD/branch backend
feat/i3-backend-checkpoint/0ce87f5; AI feat/update-mcp-contract/426ae08; índices
vazios. Snapshots novos .i3-inverse, separados dos anteriores .i3-resumption.

Defeito reproduzido: recuperação vencedora gravava RECOVERY_NOTICE; o turno
originário tardio recuperava esse texto com actionExecuted=true. Sucesso e timeout
AI originários exibiam erroneamente "Sua nova mensagem não foi processada".
Agora ConversationService envia apenas a composição/fallback da operação a
finish. Após o retorno da transação, usa o booleano interno recovering, definido
pelo ramo de orquestração confiável, para criar o aviso HTTP de cada recuperação.
O turno originário usa a composição aceita sem esse aviso, com o booleano de
execução confirmado. Não se remove prefixo por heurística, não se consulta texto
AI para decidir contexto e não há classificação LLM de "retomar".

Resultado persistido independente do vencedor: uma composição final por recibo.
id/role/createdAt do MessageResponse HTTP permanecem os da mensagem aceita; seu
content na recuperação tem o aviso adicional. GET messages retorna a composição
persistida sem esse aviso. A projeção HTTP não escreve no histórico nem muda o
recibo; a mensagem de entrada continua salva. A falha antes da conclusão pode
gravar fallback neutro no histórico e deixar composition_pending=true. Se outra
chamada já concluiu, até um timeout tardio retorna a mensagem vencedora, sem novo
fallback ou finalização e sem falsa indicação de composição pendente.

Nenhuma alteração em ConversationTurnStore, execução financeira, associação
originária, ownership, locks/commit, V4/V5 ou DTOs/endpoints. Preservadas pendências
mais recentes, expiração, código e guardrails I1/I2. Chamadas AI concorrentes
continuam possíveis e fora das transações. A separação segue Constituição III/IV/
V/IX/X/XI e SEC-02/referências OWASP já adotadas: autoridade backend, isolamento,
integridade e contexto confiável separados de conteúdo. Sem alegação de auditoria
integral/conformidade OWASP, novas políticas ou governança.

### Evidências novas deste ajuste

Reprodução contra o código imediatamente anterior: **2 testes, 2 falhas
esperadas**, mostrando o aviso indevido no turno originário em sucesso e timeout.
Com a correção: **36 testes afetados aprovados**; Maven verify completo passou
com **148 testes, zero falhas/erros/skips**, incluindo 30 casos PostgreSQL/Spring
reais (3 testes novos e cenários existentes fortalecidos). JaCoCo: linhas
85.53%, instruções 85.94%, branches 75.00%.
Nova regressão AI: **110 passed, dois warnings existentes, 0,92 s**, Python 3.12,
contratos/recibos/guardrails/I1/I2. Contagens 145/110 da etapa anterior são históricas.
POST e GET MockMvc exercitam serialização/controller/filtro JWT reais, além dos
proxies e PostgreSQL. A projeção HTTP conserva metadados da resposta de finish;
comparações entre criação e releitura consideram a precisão de microssegundos
armazenada pelo PostgreSQL (entidade recém-criada pode conter nanossegundos).

Execução inicial após restaurar a cópia fixada ainda usou bytecode antigo: o
timestamp preservado fez o compilador incremental ignorar a fonte corrigida.
Fonte recompilada, testes finais executados novamente. Ajustada a asserção de
timestamp à precisão real do banco; nenhuma asserção de conteúdo/histórico/origem
ou concorrência foi relaxada. Logs/XML separados em .i3-inverse preservam essas
rodadas. Avisos Flyway/PG17, Java agent/CDS/dialect e Starlette/AnyIO preexistentes.
Comando AI: python -B -m pytest -q -p no:cacheprovider
tests/integration/test_execution_confirmation.py
tests/guardrails/test_confirmation_guardrails.py tests/contract/test_service_boundary.py
tests/contract/test_i2_delivery.py tests/unit/test_i1_correlation.py.


PostgreSQL 17.11 descartável: cluster próprio .i3-inverse/pgdata, porta 15485,
127.0.0.1/junta_i3_disposable_inverse_20261011, V1–V5 e Hibernate validate;
JDK 21.0.12 / Maven 3.9.16 configurado no wrapper. Não usado banco compartilhado,
produtivo ou padrão. Latches/barreira controlam vencedor e liberação tardia.
Spring/proxies/locks/commit/persistência reais; AI simula só transporte/timeout.
Mocks SMTP herdados impedem envio externo. Sem dependência nova.

Cobertos: recuperação vence + sucesso originário; recuperação vence + timeout
originário; turno originário vence; duas recuperações concorrentes; pendência nova
após vencedor antes da resposta tardia. Em todos: único efeito financeiro, único
recibo/finalização, reutilização do id aceito, booleanos e aviso corretos,
composição armazenada neutra, origem/request_id/recibo/efeito preservados e GET
messages inalterado pela resposta tardia. Fallback anterior permanece intacto
quando a recuperação conclui; falha continuada não recebe composição final.

Comando backend: Maven -o -q -Dmaven.repo.local=<cache existente>
-Dlogging.level.root=WARN -Ddebug=false; afetados com
-Dtest=ActionExecutionPostgresTest,ConversationServiceTest,ConversationIntegrationTest
test; regressão completa verify. I3_DISPOSABLE_DB=true e DB_URL exclusivo acima.
Avisos existentes de Flyway/PG17 e Java agent/CDS/dialect permanecem registrados;
não alteradas dependências/configuração para ocultá-los.

Limites: não há deduplicação de chamadas AI, consentimento, retomada expirada,
S2S, deduplicação entre action_ids, adapter MCP, US2, políticas ou produção
aprovados. O ajuste não reescreve composições históricas já persistidas pela
versão anterior nem tenta remover avisos de seus textos. Sem migration nova.

Diffs incrementais contra snapshots imediatamente anteriores deste ajuste:
revisao/I3-inverse-backend.diff e revisao/I3-inverse-ai-docs.diff no AI Service.
Todos os diffs/artefatos anteriores preservados. Sem staging/commit/push/merge/
PR/deploy ou alterações GitHub; checkpoints permanecem manuais.
