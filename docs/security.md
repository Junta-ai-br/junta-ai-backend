# Segurança — junta-ai-backend

> Status: **Implemented** (itens marcados ✅) / **Planned** (demais), seguindo
> o checklist de `junta-ai-arquitetura-roadmap-mvp.pdf`, seção 6.

- ✅ **Autenticação, sem senha local**: decisão do time (`sec-02-authentication.md`
  do `junta-ai-internal`), implementada. Duas portas de entrada:
  - **Google**: o Backend valida o ID Token contra o endpoint público do
    Google (`GoogleAuthService`) — confere `aud` (Client ID) e
    `email_verified`. Cria a conta automaticamente no primeiro acesso.
  - **Código de acesso por e-mail**: 6 dígitos, gerados com `SecureRandom`,
    guardados com **hash BCrypt** (não em texto puro), expiram em 10
    minutos (configurável) e só podem ser usados uma vez
    (`EmailAccessCodeService`). Só funciona para contas já cadastradas.
  - Access token JWT (15 min) + refresh token opaco revogável, exatamente
    como antes — essa parte não mudou com a troca do mecanismo de login.
- ✅ **Envio de e-mail real**: `GmailEmailSender` usa Spring Mail e SMTP
  do Gmail/Google Workspace exclusivamente para entregar o código recebido.
  Geração do OTP, hash BCrypt, expiração, uso único e validação continuam
  no backend (`EmailAccessCodeService`); Gmail não autentica nem emite tokens.
  Configure `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` e
  `MAIL_FROM` no ambiente, sem versionar credenciais. O corpo usa a mesma
  propriedade `auth.access-code.expiration-minutes` da validação.
  A conexão exige STARTTLS e possui limites de espera de cinco segundos.
  Falhas retornam HTTP 503 com mensagem amigável, sem registrar código,
  destinatário, credenciais ou detalhes da exceção SMTP.
  `EmailSenderConfig` registra exatamente um `EmailSender`: Gmail com
  configuração completa; console somente com profile `local` e/ou `dev`
  explicitamente ativo, sem outros profiles e sem usuário, senha ou remetente
  SMTP preenchidos. O console imprime o código para testes locais.
  Não existe profile de produção explícito: sem profile, em qualquer outro
  profile (incluindo `docker`, `prod` e `production`) ou com credenciais
  parciais, a configuração incompleta impede a inicialização.
  No Google, habilite a verificação em duas etapas e gere uma senha de app
  quando disponível para a conta; use-a em `MAIL_PASSWORD`, nunca a senha
  normal da conta. A política do Workspace pode restringir senhas de app.
  `MAIL_FROM` deve ser a conta ou um alias autorizado; o ambiente precisa
  permitir saída para o servidor SMTP na porta configurada.
- ✅ **Cadastro com e-mail confirmado**: a conta só é criada depois de
  confirmar o código recebido no e-mail (`/auth/register/request-code` +
  `/auth/register/verify`). Nada é gravado antes disso.
- ✅ **Força bruta no código de 6 dígitos**: máximo de 5 tentativas por
  código (contagem atômica no banco, vale com requisições paralelas),
  só o código mais recente vale, 30 s entre pedidos e no máximo 5 pedidos
  por e-mail a cada 15 min. Configurável por `ACCESS_CODE_*`.
- ✅ **Limite por IP em `/auth/**`** (`AuthRateLimitFilter`, 429): pedir
  código 10, verificar 20, demais 60 a cada 10 min. Em memória, por
  instância; com várias réplicas o limite efetivo é multiplicado, por isso
  os limites por e-mail/código (no banco) são a garantia principal.
- ✅ **Limite do chat por usuário** (`ChatRateLimiter`): 20 mensagens/min;
  cada mensagem aciona a IA (lenta e paga).
- ✅ **Login sem revelar contas**: pedir código de login para e-mail sem
  conta responde 202 igual e não envia nada. Código inexistente, expirado,
  errado ou conta inexistente dão a mesma mensagem. *Limitação conhecida:*
  o cadastro responde 409 para e-mail já cadastrado (decisão de UX do time);
  o limite por IP/e-mail reduz o uso dessa rota para listar clientes.
- ✅ **JWT_SECRET obrigatório**: a aplicação não sobe sem segredo de pelo
  menos 32 caracteres nem com os valores de exemplo. O filtro JWT usa só as
  claims assinadas (sem consulta ao banco por requisição); consequência:
  um usuário apagado mantém o access token válido até 15 min.
- ✅ **Refresh token com revogação**: token opaco de 256 bits; o banco guarda
  só o **hash SHA-256** (vazamento do banco não dá acesso às contas). Rotação
  atômica a cada uso, expiração de 7 dias, logout idempotente. Se um token
  já usado reaparecer (possível roubo), todas as sessões do usuário são
  encerradas. Limpeza diária dos vencidos.
- ✅ **Autorização por propriedade**: toda consulta a Category/Transaction/
  Goal/Conversation é filtrada por `user_id`; ver `docs/architecture.md`.
- ✅ **SQL Injection**: 100% JPA/Hibernate com parâmetros nomeados
  (`@Query` usa `:param`), nunca concatenação de string.
- ✅ **CORS restrito**: origens configuráveis via `CORS_ALLOWED_ORIGINS`
  (não é `*` em produção).
- ✅ **CSRF**: desabilitado deliberadamente — a API é *stateless* e usa
  Bearer token, não cookies de sessão (CORS não substitui CSRF, mas aqui
  também não há CSRF a proteger, pois não há cookie de autenticação).
- ✅ **Secrets fora do código**: `JWT_SECRET`, credenciais de banco e URL do
  serviço de IA vêm de variáveis de ambiente (`.env.example` documenta as
  chaves esperadas, nunca valores reais).
- ✅ **Logs sem dados sensíveis**: nenhum log imprime senha, token ou valor
  financeiro além do necessário para depuração.
- 🟡 **XSS**: mitigado por padrão (API JSON, sem renderização de HTML no
  backend); a responsabilidade de escaping de conteúdo dinâmico é do
  frontend ao exibir mensagens de chat.
- 🟡 **Threat modeling (STRIDE)**: a ser formalizado em documento próprio
  (`threat-model.md`) junto com o time; este backend já assume as
  fronteiras de confiança descritas no roadmap (Frontend não confiável,
  IA não confiável para escrita, Backend como única autoridade).
- ✅ **Carga**: pool Hikari configurável (padrão 10, espera máx. 5 s), chat
  sem transação aberta durante a chamada à IA (não prende conexão),
  paginação com teto de 100, ordenação fixa, consultas do chat limitadas,
  corpo de requisição até 64 KB (413), shutdown graceful.
- ✅ **Planejador isolado por usuário**: toda consulta de
  `planner_simulations` filtra por `user_id` do JWT; de outra pessoa = 404.
- ⬜ **Ainda não coberto** (próximos passos): rate limit global entre
  réplicas (Redis ou gateway), envio de e-mail assíncrono (hoje o SMTP
  roda na requisição, com timeout de 5 s), revogação imediata de access
  token, MFA, WAF/proteção DDoS no Azure.

## Por que o refresh token não é um JWT

Um JWT de refresh só pode ser invalidado com uma blacklist (mais um estado
para gerenciar). Preferimos um valor opaco gravado em banco: revogar é um
`UPDATE`/`DELETE`, e a rotação a cada uso limita o dano de um token
roubado a uma única troca.
