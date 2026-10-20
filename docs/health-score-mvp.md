# Health Score mensal do MVP — decisão de 20/10/2026

Indicador educativo, sem finalidade de crédito ou diagnóstico profissional.
Metodologia `mvp-monthly-v1`, adaptada e aprovada pelo produto nesta entrega.

Fonte: somas de receitas e despesas persistidas do usuário autenticado, no mês
civil UTC completo (mesma política do provider financeiro canônico). Não usa
nomes de categorias, amostras recentes, frequência de uso ou transações planejadas.

Com receita R e despesa D, ambas positivas:

- Capacidade de poupança P = limitar(500 × (R − D) / R, 0, 100).
- Comprometimento total C = limitar(100 − 200 × D / R, 0, 100).
- Equilíbrio mensal E = 100 se R − D >= 0; caso contrário, 0.
- Score = arredondar(0,45P + 0,35C + 0,20E), HALF_UP para inteiro.

Preservadas as curvas e os pesos originais. Os componentes têm correlação;
equilíbrio mensal é binário, não regularidade histórica.

Receita ou despesa igual a zero: `INSUFFICIENT_DATA`, score e classificação null.
Receita é o denominador; despesas observadas são necessárias para avaliar
comprometimento sem classificar uma conta contendo apenas receitas como excelente.
Não se exige quantidade arbitrária de registros. Períodos diferentes do mês
corrente não recebem indicador (healthScore null).

Faixas: 0–24 Vamos cuidar disso; 25–44 Hora de ajustar; 45–59 Em evolução;
60–79 No caminho certo; 80–100 Excelente! Tudo tranquilo por aqui!

O GET /dashboard existente recebe o campo aditivo healthScore, com methodology,
status, score, classification, from e to. O mesmo DTO calculado pelo DashboardService
é incluído em FinancialStateSnapshot.indicators.healthScore, inclusive no snapshot
após execução confirmada. Não há novo endpoint, persistência de score ou alteração MCP.

Frontend e IA apenas validam/apresentam o resultado. O frontend compara os totais
e o período do dashboard com a leitura canônica completa antes de exibir o score.
Divergência ou falha torna o indicador indisponível até nova leitura. Envios pendentes
e resultados incertos ocultam o indicador. A barra de 50% em Em avaliação existe
somente na apresentação; não integra o DTO nem o cálculo. Não há evolução fictícia.
