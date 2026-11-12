# Reconhecimento de contribuições para metas

Extensão aditiva e retrocompatível: `ExecutionConfirmation.goal_recognition`,
versão `goal-recognition-v1`. Ausência/null mantém o incentivo neutro no AI Service.
Recibos antigos continuam sendo lidos pelo backend e reenviados sem reconstrução.

## Fonte de verdade e persistência mínima

A migration **V14__goal_recognition.sql** acrescenta dois campos opcionais a `goals`:
`confirmed_contribution_count` e `recognized_milestone`. Uma constraint exige ambos
nulos ou contagem não negativa e marco em `0,25,50,75,100`.

Os recibos anteriores não registram marcos já reconhecidos de maneira uniforme;
o acumulado não permite derivar contagem ou reconhecimento. Por isso, metas
legadas ficam com ambos os campos nulos. Apenas `GoalService.create`, incluindo
importação do planejador que o reutiliza, inicia cobertura conhecida em zero.
Não há backfill nem nova tabela, serviço, fila ou contador no AI Service.

`GoalService.addProgress` atualiza posição, contagem e maior marco coberto na
mesma transação, nos locks existentes de usuário/meta. HTTP e chat reutilizam esse
caminho. Rejeições e rollbacks não contam. A idempotência existente de
`goal_command_receipts` e `action_executions` impede um segundo efeito em replay.
Retirada e alteração do alvo não reduzem o histórico de reconhecimento.

Um cruzamento usa comparação decimal exata:
`antes × 100 < marco × alvo ≤ depois × 100`. A alteração isolada do alvo não
produz contribuição nem copy. Um salto cobre todos os marcos inferiores, mas a
resposta recebe somente o maior novo marco. Marcos de aportes HTTP ficam cobertos
também; não são reapresentados retrospectivamente em outra contribuição no chat.

## Recibo congelado

`ActionExecutionService` inclui a evidência somente em aporte bem-sucedido com
cobertura conhecida. Ela fica no JSON do recibo já persistido, sem alterar o
mecanismo de idempotência ou a identidade da mensagem de composição.

Campos: `version`, `action_id`, `goal_id`, `contribution_number`, `before_amount`,
`after_amount`, `target_amount`, `recognized_before`, `recognized_after`,
`new_milestone` e `status`. Dinheiro é transmitido como strings decimais exatas.
`new_milestone` pode ser null. Para 100%, posição e status devem comprovar conclusão.
O recibo original é reutilizado em replay mesmo depois de outros aportes.

O AI Service valida correlação e consistência contra a ação confirmada e o
snapshot, então aplica percentual → quantidade → neutro. Não usa saldo como
histórico nem infere pausas ou hábitos sustentados. Evidência ausente/desconhecida
ou inválida não apaga a confirmação financeira: apenas impede a celebração numérica.

## Verificação e integração

`src/test/resources/goal-recognition-contract.json` é a mesma fixture do AI Service.
`GoalRecognitionPostgresTest` verifica contagem, marcos, replay entre canais,
concorrência, legado, constraints e serialização. `GoalRecognitionHttpPostgresTest`
usa os dois serviços reais em loopback e PostgreSQL descartável, sem Gemini,
para confirmar as copies da 1ª à 6ª, 75% em salto e conclusão de 100%.

Nesta entrega, a migration foi executada somente no banco descartável local.
Ordem recomendada: backend/V14 antes do novo AI Service; frontend independente.
Não executar migrations remotas ou deploy como parte da abertura das PRs.
