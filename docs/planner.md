# Planner: persistência de simulações

Branch baseada em `57428ef` (checkpoint I3.1). O incremento do Planner contém
somente arquivos novos e não altera conversas, autenticação ou operações financeiras.
A migration `V7__planner_simulations.sql` sucede V1–V6 já presentes nessa base.
Esta branch inclui o checkpoint como ancestral; não foi criada a partir de `main`.

## API

Todas as operações exigem `Authorization: Bearer <access_token>`.

- `POST /planner/simulations`: 200 com a simulação persistida.
- `GET /planner/simulations`: 200 com array do usuário, mais recentes primeiro.
- `DELETE /planner/simulations/{id}`: 204; 404 para ID inexistente ou de outro usuário.

Exemplo de criação:

```json
{
  "goalName": "Viagem",
  "goalAmount": 12000.00,
  "deadlineMonths": 12,
  "analysisBase": "last_3_months",
  "monthlyAmount": 1000.00,
  "estimatedMonths": 12,
  "historyContext": {
    "income": 4000.00,
    "fixedExpenses": 1000.00,
    "totalExpenses": 3000.00,
    "available": 1000.00,
    "commitmentRate": 75
  }
}
```

A resposta acrescenta `id` e `createdAt`, definidos pelo servidor. Não há `userId`
no contrato; a propriedade vem exclusivamente do principal autenticado.
Consultas e exclusões incluem o UUID desse usuário.

`goalName` é obrigatório, até 150 caracteres. Valores monetários usam no máximo
17 dígitos inteiros e 2 decimais; meta e aporte devem ser positivos.
Prazo desejado: 1–1200 meses. Estimativa: 1–12000 meses.
`analysisBase`: `current_month`, `last_30_days`, `last_3_months` ou `no_history`.
Payload inválido retorna 400; sem autenticação, 401.

`historyContext` é opcional. Quando informado, seus cinco agregados são
obrigatórios; receitas e despesas não podem ser negativas. `available` admite
saldo negativo e `commitmentRate` admite mais de 100%, com até quatro decimais.
O frontend deve normalizar a precisão antes do envio.
O snapshot JSONB não contém transações e não é fonte oficial de saldos.
Não há recálculo, edição ou conversão em `Goal`.

## Adaptação futura do frontend

Mapear `goal.name/amount/deadlineMonths` para `goalName/goalAmount/deadlineMonths`,
`period.type` para `analysisBase`, e os campos de `simulation` para
`monthlyAmount/estimatedMonths`. Rótulos e `usesHistory` podem ser derivados do
período. Aguardar o sucesso HTTP antes de atualizar o histórico; usar os IDs e
datas retornados pelo backend. Os cálculos permanecem no React.

## Validação

`PlannerSimulationIntegrationTest` usa a infraestrutura de integração existente
com PostgreSQL real configurado por `DB_URL`, `DB_USER` e `DB_PASSWORD`.
Executar somente em banco de testes: o Spring aplica Flyway ao iniciar.
O teste cobre persistência do snapshot, ausência de histórico, vínculo ao usuário
autenticado, isolamento de listagem/exclusão, payloads inválidos e acesso anônimo.
