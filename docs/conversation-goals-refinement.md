# Metas: disponibilidade acumulada e escolhas vinculadas

Base: 783e33b. Entrega coordenada com AI Service 46dbb39 e frontend 581ad1f mais seus incrementos correspondentes.

O saldo disponivel e o total acumulado de receitas menos despesas realizadas ate a data atual em America/Sao_Paulo, descontadas todas as reservas em metas do usuario. A consulta /dashboard acrescenta availableBalance, totalTracked e reservationInconsistent. balance continua sendo receitas menos despesas do periodo solicitado; totalIncome, totalExpense e Health Score nao mudam.

Nao ha migracao, ledger, infraestrutura ou alteracao silenciosa de historico. Quando as reservas excedem o total registrado, a inconsistencia e explicita e novas reservas sao bloqueadas. Retiradas continuam disponiveis dentro dos limites existentes. Nenhum aporte ou retirada cria receita ou despesa.

As escritas financeiras e de metas usam o bloqueio existente da linha do usuario antes dos bloqueios dos recursos e das insercoes de recibos. Isso serializa reservas em metas distintas e as alteracoes de movimentacoes que afetam o mesmo disponivel. Os recibos continuam garantindo replay sem repetir efeitos. A leitura do dashboard usa uma transacao consistente.

QuickReplySelection preserva option_id, pending_id e conversation_id; display_label e opcional e serve apenas para exibicao. As opcoes admitidas sao source_registered, source_unregistered, confirm, cancel e goal:<UUID> presente em goal_candidates da pendencia. A selecao verifica a propriedade e o nome atual da meta. A execucao revalida o UUID confirmado e seu nome, sem escolher a primeira meta homonima. source nao substitui confirmacao financeira.

Cada prompt persistido recebe uma nova identidade. Escolhas e confirmacoes associadas ao prompt expiram em dez minutos. Replays da mesma chave recuperam o resultado anterior; a expiracao nao autoriza repetir o efeito. O backend rejeita pendencias sem uma mensagem de prompt valida para escolhas interativas.

O fluxo de receita nova seguido de reserva permanece em duas etapas, conforme a alternativa aprovada: a fronteira atual executa uma operacao por recibo. Nao se simula atomicidade entre duas chamadas nem se cria automaticamente uma receita. O usuario registra a entrada pelo fluxo existente e solicita a reserva em seguida.

Evidencia: testes unitarios e PostgreSQL descartavel real cobrem R$ 2000/150/1850, retirada sem nova receita, replay, concorrencia entre duas metas, inconsistencias antigas, propriedade, confirmacao e expiracao. Nao houve acesso a bancos ou usuarios de producao.
