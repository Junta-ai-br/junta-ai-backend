# Coordenação das escritas financeiras — PR 1

Data: 18/11/2026. Base: `e0f959e970bcf7a5b76438236854a5c54ea9e2a0`.
Branch: `fix/backend-financial-write-coordination`, worktree isolada
`.worktrees/financial-write-coordination`.

## Problema e escopo

O CRUD de transações e a exclusão de conta já bloqueiam a linha do usuário antes
das escritas. O chat bloqueava conversa antes de usuário na admissão/execução;
finalização e recuperação também bloqueavam conversa sem coordenar pelo usuário.
Isso permite inversão contra uma operação que já detenha o usuário e precise
dos recursos da conversa. A importação direta de simulação também podia bloquear
a simulação antes de obter o lock do usuário por uma FK na persistência da meta.

A alteração antecipa/reutiliza o lock pessimista do usuário, sem alterar payloads,
consentimento, autorização, TTL de dez minutos, recibos ou regras de domínio.
Não cria tabelas, migrations, endpoints, entidades FR-017, normalização, versão de
Transaction, dependências ou política de retenção. Não inicia PR 2, I2 ou I3.

## Mapa da ordem efetiva

U = usuário; C = conversa; E = ingresso; R = recibo; T = transação;
G = meta; S = simulação. Os locks são do PostgreSQL, não de um mutex em memória.

| Entrada | Antes | Depois |
|---|---|---|
| MessageEntryStore.admit | C → E → U (provisionamento/FKs, quando necessário) | U → C → E, mantendo validações e provisionamento |
| MessageEntryStore.select/failInitial/completeResponse, via owned | C → E | U → C → E |
| ConversationTurnStore.begin/completed/finish, via owned | C → E/R quando aplicável | U → C → E/R quando aplicável |
| ActionExecutionService.execute | C → U → E/R → T/G | U → C → E/R → T/G |
| ActionExecutionService.recordedReceipt | C → R | U → C → R |
| PlannerGoalImportService.importGoal direto | S → persistência de G/FK de U | U → S → persistência de G |
| GoalCommandService → importGoal | U → recibo HTTP → S → G | Mesma ordem; importGoal readquire U na mesma transação |
| TransactionService.create/update/correct/delete | U → T, quando existente | Inalterado |
| CategoryService.create/update/delete | U → categoria | Inalterado |
| GoalService.update/addProgress/withdrawProgress/delete | U → G | Inalterado |
| GoalCommandService.create/progress/delete | U → recibo HTTP → G | Inalterado |
| AccountDeletionService.deleteAccount | U → ingressos/recibos → transações → demais dados/cascatas | Inalterado |

Criação HTTP de metas entra por GoalCommandService, que já adquire U. A criação
de conversa e operações isoladas de histórico de simulações não introduzem uma
aquisição tardia explícita de U; não foram ampliadas nesta PR. Consultas sem locks
e sem escrita permanecem fora da serialização adicional.

## Chamadas aninhadas e propagação

- MessageEntryStore e ConversationTurnStore mantêm `@Transactional` com propagação
  REQUIRED. `completeResponse → turns.finish` participa da mesma transação;
  readquirir U/C usa a mesma conexão, sem suspender locks.
- GoalCommandService e PlannerGoalImportService também permanecem REQUIRED.
  O lock adicional no importador cobre a chamada direta e é reentrante no wrapper.
- ActionExecutionService.execute mantém o **REQUIRES_NEW já existente**. Nenhum
  REQUIRES_NEW foi adicionado. A orquestração ConversationService.sendMessage não
  tem transação abrangente: admissão/seleção terminam antes da execução.
- TransactionService/GoalService chamados pelo executor unem-se à transação
  financeira existente. Efeito e recibo continuam confirmados juntos.
- Interpretação e composição via AiClient permanecem entre etapas transacionais,
  sem retenção de U/C. Os testes verificam ausência de transação ativa e permitem
  a outra conexão adquirir ambos os locks com NOWAIT durante cada chamada à IA.

Chamadas futuras ao executor a partir de uma transação externa que já retenha U
continuam proibitivas: suspender essa transação com REQUIRES_NEW pode provocar
auto-bloqueio. Esta PR preserva as fronteiras existentes, sem criar tal chamada.

## Evidência reproduzível

O baseline foi executado antes da alteração dos quatro serviços. PostgreSQL
16.15 portátil, Windows x64, cluster próprio em loopback, sem alterar o serviço
PostgreSQL 18 instalado. Binário obtido da EDB; SHA-256 do ZIP:
`25e6fcdfb8caec38691bf461125e7564508760666f7b8e5dc6a5f0818f58f81e`.
Hash registrado para rastreabilidade, sem alegação de checksum publicado pelo fornecedor.

Os bancos exclusivos têm o prefixo `junta_i3_disposable_lock_`. A porta 54837 foi
usada após confirmar disponibilidade, pois testes anteriores exigem essa porta.
PG16 e PG18 foram executados sequencialmente em clusters separados. Somente
dados sintéticos foram inseridos. Nenhum banco compartilhado/remoto foi usado.

| Execução | Resultado |
|---|---|
| Baseline unitário direcionado | 87 aprovados; zero falhas/erros/ignorados |
| Novos testes de ordem contra a produção intacta | 21 casos: 11 aprovados e 10 negativos (7 falhas de espera por U e 3 timeouts ao tentar bloquear recurso já tomado) |
| Novos testes após correção, PG16.15 | 26 aprovados; zero falhas/erros/ignorados |
| Baseline amplo PG16.15 | 517 casos: 457 aprovados, 22 falhas, zero erros, 38 ignorados |
| Candidato amplo PG16.15 | 543 casos: 483 aprovados, as mesmas 22 falhas, zero erros, 38 ignorados |
| Candidato amplo PG18.6 | 543 casos: 505 aprovados, zero falhas/erros, 38 ignorados |

Comparação dos XMLs confirmou exatamente os mesmos 22 IDs e causas no baseline e
no candidato PG16. Todos exigem `server_version_num` entre 180000 e 189999:
MessageIdempotencyPostgresTest (21) e MessageEntryMigrationPostgresTest (1).
Esses testes não foram modificados, filtrados ou considerados aprovados em PG16.
A validação nova de coordenação em PG16 está aprovada; a suíte integral nessa
versão permanece limitada por esses pressupostos dos testes preexistentes.

Os 26 casos usam duas JVMs com contextos Spring e pools independentes. Apenas o
provedor de IA é simulado; locks, FKs, efeitos e recibos usam PostgreSQL real.
Uma conexão retém U, a segunda instância solicita a operação, e pg_blocking_pids
comprova a espera. A primeira conexão verifica que C/T/categoria/G/S permanecem
disponíveis até a liberação de U. Não se usa apenas um atraso para inferir disputa.
Na corrida de confirmações equivalentes, o teste só libera U depois de observar
as duas instâncias na cadeia de espera, incluindo bloqueios transitivos da fila
do PostgreSQL; não depende de coincidência de escalonamento dos processos.

São cobertos 21 caminhos de serviço, CRUD versus confirmação de chat, confirmação
equivalente entre processos, conflito de corpo/chave, usuário independente,
conversa estrangeira e ambas as chamadas à IA sem locks financeiros. O resultado
`message_processing_in_progress` durante uma corrida equivalente permanece válido:
a retomada explícita com a mesma chave recupera um único efeito/recibo, sem retry
financeiro automático. Os testes anteriores de rollback, timeout, reconciliação,
confirmação expirada, metas e exclusão também integram a regressão ampla.

### Comandos

Configurar somente no processo de teste: DB_URL de banco loopback com o marcador
descartável, DB_USER/DB_PASSWORD sintéticos, `I3_DISPOSABLE_DB=true` e JWT_SECRET
sintético forte. Não usar credenciais ou dados de produção. Runtimes frios dos
testes antigos exigem JWT_SECRET no ambiente, além da configuração do teste pai.

```text
mvnw.cmd -o -Dtest=FinancialWriteCoordinationPostgresTest test
mvnw.cmd -o test
```

O CI conserva o teste normal e acrescenta um passo direcionado em banco exclusivo
no serviço PostgreSQL 16 já existente. O flag descartável só é habilitado nesse
passo, para não ativar involuntariamente as suítes antigas que exigem PG18.
Validação estrutural do YAML confirmou gatilhos e jobs de publicação/deploy
idênticos à base; nenhuma publicação ou implantação foi executada.

## Cobertura e limites

JaCoCo 0.8.12, relatório gerado após remover os dados acumulados das execuções
anteriores. Cobertura de **linhas**, medida na regressão PG18 final:

| Escopo | Linhas cobertas/total | Cobertura |
|---|---:|---:|
| Aplicação | 1703/1861 | 91,51% |
| MessageEntryStore | 123/133 | 92,48% |
| ConversationTurnStore | 158/166 | 95,18% |
| ActionExecutionService | 203/213 | 95,31% |
| PlannerGoalImportService | 15/16 | 93,75% |

O relatório mede a JVM de teste instrumentada; não agrega cobertura da segunda
JVM. A execução de serviços na segunda instância é demonstrada pelos cenários,
não convertida artificialmente em cobertura. PG16 amplo mediu 1503/1861 linhas
(80,76%), com as limitações de versão descritas acima.

Os 38 ignorados pertencem às suítes opcionais CategoryStandardKeyMigration (1),
GoalConversationContract (9), GoalRecognitionHttp (3), InitialCategoryProvisioner
(17), MvpFinancialFlowIntegration (4) e TransactionConfirmationHttp (4). Não são
evidência aprovada. Não houve integração HTTP com AI Service real nem provedor
pago; o contrato existente não foi alterado. A execução local comprova PostgreSQL
nativo nessas versões, não equivalência integral à imagem Alpine ou produção.

## Riscos residuais e entrega

Há maior serialização das etapas curtas do chat para o mesmo usuário, incluindo
finalização/recuperação. Usuários distintos não compartilham um lock global; o
teste correspondente passou. Não foi realizado ensaio de carga ou dimensionamento
de pool. A ordem depende de futuros escritores manterem o mesmo protocolo.

Nenhum comportamento financeiro existente foi intencionalmente alterado. Não
houve mudança em AI Service, frontend, contratos, prazo de confirmação, retenção,
dependências ou migrations. Trabalho local anterior foi preservado. A entrega
termina na PR 1 para revisão; sem merge, bypass, deploy ou início da PR 2.
