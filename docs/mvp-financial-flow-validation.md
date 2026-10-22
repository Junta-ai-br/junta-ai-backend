# Validação P0 — fluxo conversacional e financeiro

Bases: backend `d1ee61cf611af5897a3f984fa64027950ee8923a`; IA `d9818743af629313577bbb7eeea70f969a8ca3f1`.

## Divergência reproduzida

A consulta `quanto gastei este mês?` recebia agregados de 90 dias do backend. A IA corretamente recusava comprovar o recorte mensal, mesmo quando recebia um snapshot mensal em outros testes. Um teste real com R$ 25 no mês e R$ 75 no mês anterior reproduziu a resposta com R$ 100 do período de 90 dias.

Correção: `ConversationTurnStore` fornece agregados do primeiro dia do mês até a data de negócio em America/Sao_Paulo nos campos existentes `current_state`, `periodFrom` e `periodTo`. A amostra recente continua limitada a dez registros dos últimos 90 dias; Health Score conserva seu cálculo anterior. A IA reconhece a consulta mensal somente se esse período corresponder exatamente ao mês até a data de negócio confiável. Não soma a amostra, não escreve no banco e continua recusando recortes não comprovados. Não há novos campos nem mudança de schema entre serviços.

## Evidência local

- Backend: 21 testes direcionados aprovados: transporte (2), catálogo (6), orquestração (5), regressões PostgreSQL (4) e integração real (4).
- IA: 62 testes direcionados aprovados: catálogo HTTP e continuidade selecionados (6), consulta e confirmação com sete novos casos mensais (42), regressões de recorte (14).
- A integração real usa AiClient/RestClient, FastAPI/Uvicorn e PostgreSQL 18.6 descartável com as nove migrations. Não substitui AiClient por mock. Somente o envio de e-mail é isolado pela infraestrutura de testes existente.
- Uber e supermercado selecionam categorias autorizadas; `gastei 25 reais` seguido de `alimentação` conclui e limpa a pendência; receita persiste; replay da mesma chave não duplica; recibos SUCCESSFUL e lançamentos são conferidos por SQL.
- Consulta mensal retorna R$ 25, exclui o mês anterior e não cria recibo de execução. Mudança concorrente de categoria para tipo incompatível gera recibo REJECTED, zero lançamentos e resposta da IA sem confirmação. Conversa de outro usuário não executa.
- Regressões existentes conferem rollback real após falha posterior à escrita, idempotência, expiração e isolamento. Nessas regressões o transporte IA é controlado; não são apresentadas como integração HTTP real.
- PostgreSQL 18 emite aviso de versão mais nova que a homologada pelo Flyway existente; as migrations e verificações de persistência passaram. Docker estava indisponível; não foi necessário alterar infraestrutura existente.

## Como repetir a integração real

Use banco local descartável cujo nome contenha `junta_i3_disposable_`; configure DB_URL, DB_USER e DB_PASSWORD apenas no processo de teste. O teste verifica esse marcador antes de inserir fixtures.

No worktree da IA, com as dependências do projeto instaladas:

```powershell
$env:PYTHONPATH = 'src'
$env:JUNTA_ORCHESTRATION_ENABLED = 'true'
python -m uvicorn junta_ai.api.app:app --host 127.0.0.1 --port 18081 --log-level warning --no-access-log
```

No worktree backend:

```powershell
$env:MVP_REAL_AI_TEST = 'true'
.\mvnw.cmd '-Dtest=MvpFinancialFlowIntegrationTest' test
```

Os testes padrão não executam essa integração sem opt-in. Não apontar o banco de teste para produção. Nenhuma chave Gemini é necessária para os fluxos determinísticos exercitados.

## Limite e próxima ação

O problema de repetição de categoria não foi reproduzido nas bases atuais, inclusive na integração real. Os fluxos de registro existentes não foram alterados. Não se afirma que o ambiente publicado esteja corrigido: após revisão e publicação manual das alterações dos dois serviços, repetir os exemplos em uma sessão autenticada e conferir histórico/dashboard com o frontend da PR existente. Nenhum código frontend ou diretório original foi modificado.
