# CI/CD do backend Spring Boot no Azure

O workflow [`deploy-backend-azure.yml`](../.github/workflows/deploy-backend-azure.yml)
executa os testes em pull requests e, após mudanças na branch `main`, publica
uma imagem identificada pelo SHA do commit no GitHub Container Registry (GHCR)
e atualiza o Container App `juntai-back-springboot`, no Resource Group
`JuntAI-BackEnd`.

Os testes do CI usam um PostgreSQL 16 temporário do próprio job. A aplicação
implantada continua usando o serviço PostgreSQL gerenciado no Azure.

O workflow não cria recursos no Azure nem altera banco, domínio, ingress ou
variáveis de runtime do app. Ele configura as credenciais de pull do GHCR e
atualiza a imagem. A configuração de produção deve estar pronta no Container App
antes do primeiro deploy.

## Segredos do GitHub

Em **Settings → Secrets and variables → Actions**, adicione estes segredos ao
repositório:

| Segredo | Uso |
| --- | --- |
| `AZURE_CLIENT_ID` | ID do aplicativo Entra ID usado pelo login OIDC |
| `AZURE_TENANT_ID` | ID do tenant Azure |
| `AZURE_SUBSCRIPTION_ID` | ID da assinatura Azure |
| `GHCR_USERNAME` | Usuário do GitHub com acesso ao pacote |
| `GHCR_TOKEN` | PAT com escopo `read:packages` e acesso ao pacote GHCR |

O token GHCR usado pelo Container App precisa ter leitura do pacote
`junta-ai-br/junta-ai-backend`. A publicação da imagem usa `GITHUB_TOKEN` com
permissão `packages: write`.

## Federação OIDC no Azure

O registro Entra ID precisa autorizar este repositório e a branch `main); a
federação criada para outro repositório não cobre este backend. No registro do
aplicativo correspondente a `AZURE_CLIENT_ID`, abra **Certificates & secrets →
Federated credentials → Add credential** e escolha a integração do GitHub Actions.
Informe a organização `Junta-ai-br`, o repositório `junta-ai-backend` e a branch
`main`. A identidade também precisa de permissão para atualizar o Container App
no Resource Group `JuntAI-BackEnd`.

## Configuração do Container App

Confirme no Azure que `juntai-back-springboot` usa a porta de destino `8080) e
está configurado para alcançar o serviço PostgreSQL gerenciado. Não coloque
segredos neste arquivo nem nos logs do workflow.

| Variável de ambiente | Requisito |
| --- | --- |
| `DB_URL` | JDBC para o servidor PostgreSQL do Azure, com o host, banco e opções TLS da instância |
| `DB_USER` / `DB_PASSWORD` | Credenciais do banco, guardadas como segredos do Container App |
| `JWT_SECRET` | Chave exclusiva de produção com pelo menos 32 caracteres, guardada como segredo |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | Todas precisam estar preenchidas; sem SMTP completo, a aplicação não inicia fora dos perfis `local` e `dev` |
| `AI_SERVICE_BASE_URL` | URL alcançável do Container App CrewAI/MCP existente |
| `CORS_ALLOWED_ORIGINS` | Origem exata do frontend, sem curinga |
| `GOOGLE_OAUTH_CLIENT_ID` | Client ID OAuth usado pelo frontend, se o login Google estiver habilitado |
| `CONTACT_EMAIL_TO` | Destinatário dos formulários públicos |

O Container App precisa ter acesso de rede ao PostgreSQL e permitir saída para o
servidor SMTP na porta escolhida. Use as configurações da instância gerenciada
existente; não copie credenciais de desenvolvimento do `compose.yaml`.

## Fluxo

1. Pull requests para `main` executam `bash ./mvnw --batch-mode test) com
   PostgreSQL 16 temporário.
2. Pushes para `main) que passam nos testes publicam
   `ghcr.io/junta-ai-br/junta-ai-backend:sha-<commit>`.
3. O job de deploy autentica no Azure por OIDC, configura o pull privado do GHCR
   e atualiza o Container App para a imagem do mesmo commit.
4. Execuções manuais fora de `main) não publicam nem implantam imagens.

Toda PR e sua descrição devem ser escritas em português do Brasil.
