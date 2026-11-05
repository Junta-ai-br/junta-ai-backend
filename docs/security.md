# Segurança — junta-ai-backend (resumo)

Este documento resume os princípios de segurança do backend. Detalhes de
implementação, limites e pendências ficam na documentação interna da equipe.

## Princípios

- **Sem senha local.** O acesso é por conta Google ou por código de 6 dígitos
  enviado por e-mail. A sessão usa um token de acesso de curta duração e um
  token de renovação revogável.
- **Autorização por dono do dado.** Cada informação pertence a um usuário e só
  ele a acessa.
- **Backend como única autoridade.** O front e a IA não são tratados como
  confiáveis para escrita financeira: toda operação é validada no backend.
- **Segredos fora do código.** Chaves e senhas vêm de variáveis de ambiente. O
  `.env` nunca é versionado, e o `.env.example` traz só os nomes das variáveis.
- **Proteção contra abuso.** Há limites de tentativas e de requisições nas rotas
  públicas e no chat.
- **Entrada validada e consultas parametrizadas.**
- **CORS restrito** às origens configuradas.
- **Logs sem dados sensíveis:** senhas, tokens e conteúdo financeiro não são
  registrados além do necessário para depuração.

## Configuração obrigatória

- `JWT_SECRET`: obrigatório, com pelo menos 32 caracteres. A aplicação não
  inicia sem ele. Gere com `openssl rand -base64 48`. Em produção, use um valor
  exclusivo, diferente do desenvolvimento.
- SMTP: fora dos perfis `local` e `dev`, todas as variáveis `MAIL_*` precisam
  estar preenchidas, senão a aplicação não inicia.
- Conta Gmail: habilite a verificação em duas etapas e gere uma **senha de app**
  para usar em `MAIL_PASSWORD`. Nunca use a senha normal da conta. A política do
  Workspace pode restringir senhas de app.

## Para quem contribui

- Nunca commite `.env`, chaves, tokens ou senhas.
- Não cole segredos em issues, PRs ou conversas.
- Rode os testes antes de abrir um PR.

## Reportar uma vulnerabilidade

Não abra uma issue pública. Avise diretamente a equipe responsável pelo projeto.