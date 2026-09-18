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
- 🟡 **Envio de e-mail real**: ainda não configurado. A implementação
  ativa (`ConsoleEmailSender`) só registra o código no log — funciona
  para desenvolvimento, mas **não pode ir para produção assim**. Quando
  o provedor for decidido (Azure Communication Services é o sugerido,
  por já estarmos na Azure), basta implementar `EmailSender` de novo.
- 🟡 **Cadastro sem verificação de e-mail**: `/auth/register` cria a
  conta sem confirmar que o e-mail pertence a quem está cadastrando —
  espelha o comportamento atual do frontend (a Etapa 2 de onboarding não
  pede confirmação). Isso não abre brecha de acesso indevido (o login por
  código exige receber o código naquele e-mail de verdade), mas permite
  cadastrar contas com e-mails de terceiros. Vale discutir com o time se
  isso é aceitável para o MVP ou se o cadastro também deveria confirmar
  o e-mail antes de liberar o uso.
- ✅ **Refresh token com revogação**: token opaco persistido em
  `refresh_tokens`, com rotação a cada uso e expiração de 7 dias.
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
- ⬜ **Rate limiting / brute-force em `/auth/login`**: não implementado
  neste MVP — próximo incremento recomendado (ex.: bucket4j ou throttling
  no Spring Security).

## Por que o refresh token não é um JWT

Um JWT de refresh só pode ser invalidado com uma blacklist (mais um estado
para gerenciar). Preferimos um valor opaco gravado em banco: revogar é um
`UPDATE`/`DELETE`, e a rotação a cada uso limita o dano de um token
roubado a uma única troca.
