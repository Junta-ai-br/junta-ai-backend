# junta-ai-backend

API REST do **Junta.ai** — Java 21 + Spring Boot 3 + PostgreSQL.
Autoridade da aplicação: autenticação, autorização, regras de negócio,
persistência e ponte com o serviço de IA (`junta-ai-ai`, CrewAI + MCP).

Este repositório implementa a arquitetura definida em:
- `junta-ai-arquitetura-roadmap-mvp.pdf`
- `junta-ai-estrutura-repositorios-e-banco.pdf`

Documentação técnica detalhada em [`docs/`](docs/):
[architecture.md](docs/architecture.md) ·
[database.md](docs/database.md) ·
[api.md](docs/api.md) ·
[security.md](docs/security.md)

---

## 1. Stack

| Camada         | Tecnologia                                   |
|----------------|-----------------------------------------------|
| Linguagem      | Java 21                                       |
| Framework      | Spring Boot 3.3 (Web, Security, Data JPA)     |
| Banco          | PostgreSQL 16                                 |
| Migrations     | Flyway                                        |
| Auth           | JWT (access) + refresh token opaco revogável  |
| Docs da API    | springdoc-openapi (Swagger UI)                |
| Build          | Maven                                         |
| Testes         | JUnit 5 + Mockito + AssertJ                   |
| Containers     | Docker / Docker Compose                       |

---

## 2. Preparando o ambiente no Arch Linux (KDE Plasma)

```bash
# JDK 21
sudo pacman -S jdk21-openjdk
sudo archlinux-java set java-21-openjdk   # garante que o 21 é o default

# Maven
sudo pacman -S maven

# Docker + Docker Compose (recomendado para não precisar instalar o Postgres direto na máquina)
sudo pacman -S docker docker-compose
sudo systemctl enable --now docker
sudo usermod -aG docker $USER   # faça logout/login (ou `newgrp docker`) depois disso

# Opcional: cliente de banco para inspecionar o Postgres visualmente
sudo pacman -S dbeaver          # ou: flatpak install dbeaver-community
```

Verifique as versões:
```bash
java -version   # deve mostrar 21.x
mvn -version
docker --version
docker compose version
```

IDE sugerida: IntelliJ IDEA Community (`sudo pacman -S intellij-idea-community-edition`
via AUR, ou baixe direto do site) — habilite o plugin/annotation processing do
**Lombok** em *Settings → Build → Compiler → Annotation Processors* e instale
o plugin "Lombok".

---

## 3. Subindo o projeto (modo rápido, com Docker)

```bash
cp .env.example .env
# edite o .env se quiser, os valores padrão já funcionam localmente

docker compose up --build
```

Isso sobe o PostgreSQL e o backend. A API fica em `http://localhost:8080`,
o Swagger em `http://localhost:8080/swagger-ui.html`.

O serviço de IA (`junta-ai-ai`) é um **repositório separado** — rode-o por
conta própria (ver README dele) e garanta que `AI_SERVICE_BASE_URL` no
`.env` apunta para onde ele está escutando (padrão: `http://localhost:8000`,
ou `http://host.docker.internal:8000` se o backend estiver em container e a
IA estiver rodando direto na sua máquina).

---

## 4. Rodando localmente sem Docker (Postgres via container, backend via Maven)

```bash
# 1. Só o banco:
docker compose up postgres -d

# 2. Exporte as variáveis (ou copie o .env.example para .env e use algo como direnv/dotenv)
export DB_URL=jdbc:postgresql://localhost:5432/junta_ai
export DB_USER=junta_ai
export DB_PASSWORD=junta_ai
export JWT_SECRET=troque-esta-chave-em-producao-min-32-chars
export AI_SERVICE_BASE_URL=http://localhost:8000

# 3. Rode a aplicação
mvn spring-boot:run
```

O Flyway aplica a migration inicial (`V1__init.sql`) automaticamente na
primeira subida.

---

## 5. Testes

```bash
mvn test
```

Os testes de integração (pacote `controller/`) precisam de um PostgreSQL
rodando **antes** de `mvn test`:

```bash
docker compose up postgres -d
mvn test
```

> ⚠️ **Mudança em relação a versões anteriores deste README**: os testes
> de integração usavam Testcontainers (Postgres efêmero, criado e
> destruído automaticamente). Isso foi trocado por um Postgres fixo via
> `docker compose` porque a biblioteca `docker-java` (usada por dentro do
> Testcontainers) tem um bug de compatibilidade com Docker Engine muito
> recente (>= API 1.55): a etapa de descoberta do Docker sempre tenta a
> API na versão 1.32, e engines novos recusam isso — nem configurar
> `DOCKER_API_VERSION` nem atualizar o Testcontainers resolveu. Se uma
> versão futura da biblioteca corrigir isso, voltar para Testcontainers é
> preferível (mais isolamento entre execuções).
>
> **Trade-off**: como o banco não é recriado do zero a cada execução, os
> dados de execuções anteriores continuam lá. Os testes já geram e-mails
> únicos por execução (`uniqueEmail()`) para não colidir, mas se quiser
> começar limpo: `docker compose down -v && docker compose up postgres -d`.

### Testes de unidade (`service/`)

Services isolados com Mockito — repositórios e `AiClient` mockados,
rápidos, sem banco nem rede:

- `AuthServiceTest` — registro sem senha, onboarding opcional, conflito de e-mail, login por código e por Google.
- `CategoryServiceTest` — criação, conflito de nome, checagem de propriedade.
- `TransactionServiceTest` — criação e bloqueio quando a categoria é de outro usuário.
- `GoalServiceTest` — progresso de meta, conclusão automática, edição bloqueada após concluída.
- `ConversationServiceTest` — orquestração com a IA: aplica ação só quando a
  confiança é alta **e** a categoria pertence ao usuário.

### Testes de integração (`controller/`)

`@SpringBootTest` + `MockMvc` + **Postgres via `docker compose`** (ver nota
acima sobre por que não é mais Testcontainers). Passam pela cadeia de
segurança completa (JWT de verdade, filtros, Flyway rodando a migration
real):

- `AuthIntegrationTest` — cadastro (já emite tokens) → login por código de e-mail (capturado via `@MockBean` no `EmailSender`) → **rotação de refresh token**
  (o token antigo, usado uma segunda vez, precisa ser rejeitado) → validação.
- `CategoryIntegrationTest` — CRUD completo e, principalmente,
  **isolamento entre usuários**: um usuário não vê, não edita e não remove
  a categoria de outro (a API responde 404, não 403 — não revela que o
  recurso existe).
- `TransactionIntegrationTest` — criação, listagem paginada, e bloqueio ao
  tentar usar a categoria de outro usuário.
- `GoalIntegrationTest` — acúmulo de aportes, conclusão automática ao
  atingir o valor alvo, bloqueio de novos aportes após concluída.
- `ConversationIntegrationTest` — fluxo completo de chat com o `AiClient`
  **mockado** (`@MockBean`): confirma que a transação sugerida pela IA é
  realmente persistida quando a confiança é alta, e que é ignorada quando
  a confiança é baixa — sem depender do `junta-ai-ai` estar no ar.

Isso já cobre os fluxos críticos exigidos pelo critério de avaliação
(CRUD, autenticação/autorização, integração). O relatório de cobertura
(JaCoCo) é gerado automaticamente a cada `mvn test`, em
`target/site/jacoco/index.html` — abra esse arquivo no navegador pra ver
o percentual atual e decidir onde ainda falta teste pra bater os **70%
mínimos** exigidos pelo curso (o candidato mais provável a precisar de
mais cobertura é o `DashboardService`, que ainda não tem teste dedicado).

---

## 6. Estrutura de pacotes

```
src/main/java/br/com/juntaai/
├── JuntaAiApplication.java
├── config/            OpenAPI, RestClient (timeouts para o serviço de IA)
├── security/          JWT, filtro, UserDetails customizado
├── entity/            User, Category, Transaction, Goal, Conversation, Message, RefreshToken
├── repository/        Spring Data JPA
├── dto/               records de entrada/saída por domínio
├── service/           regras de negócio e orquestração
├── controller/        endpoints REST
├── integration/ai/    porta de saída para o junta-ai-ai (AiClient + DTOs de contrato)
└── exception/         hierarquia de erros + handler global
```

Ver `docs/architecture.md` para a explicação de cada camada e das decisões
de design (por que o refresh token não é JWT, por que `AuthenticatedUser`
carrega o `UUID`, etc.).

---

## 7. Variáveis de ambiente

Ver `.env.example` para a lista completa. As mais importantes:

| Variável                 | Descrição                                      |
|---------------------------|-------------------------------------------------|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexão com o PostgreSQL             |
| `JWT_SECRET`               | Chave HMAC do access token (mín. 32 chars)     |
| `AI_SERVICE_BASE_URL`      | Endereço do serviço `junta-ai-ai`              |
| `CORS_ALLOWED_ORIGINS`     | Origens permitidas (ex.: URL do frontend)      |

Nunca comite o `.env` real — está no `.gitignore`.
