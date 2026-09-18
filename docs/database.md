# Banco de Dados — junta-ai-backend

> Status: **Implemented**. Migration em `src/main/resources/db/migration/V1__init.sql`.

Schema conforme `junta-ai-estrutura-repositorios-e-banco.pdf`: `users`,
`categories`, `transactions`, `goals`, `conversations`, `messages`, mais
`refresh_tokens`, `onboarding_answers` e `email_access_codes` (as três
últimas não estavam no domínio original — `refresh_tokens` e
`email_access_codes` existem por causa da autenticação sem senha,
`onboarding_answers` guarda as respostas da Etapa 2 do cadastro; todas
seguem a mesma convenção — UUID, timestamps, FK com `ON DELETE`).

## Migrations (Flyway)

- Ferramenta: **Flyway**, ativado em `application.yml`
  (`spring.flyway.enabled=true`), com `ddl-auto: validate` no Hibernate —
  ou seja, **o schema nunca é gerado automaticamente**, sempre por migration
  versionada. Isso é o que torna o histórico de mudanças de banco auditável.
- Local: `src/main/resources/db/migration/`. Próximas mudanças de schema
  devem ser criadas como `V2__descricao.sql`, `V3__...`, nunca editando o
  `V1` já aplicado em algum ambiente.

## Relacionamentos

```
users 1─* categories
users 1─* transactions   categories 1─* transactions
users 1─* goals
users 1─* conversations  conversations 1─* messages
users 1─* refresh_tokens
```

## Índices relevantes

- `transactions(user_id, transaction_date)` — consultas de dashboard e
  listagem paginada por período são o caso de uso mais frequente.
- `transactions(user_id, category_id)` — breakdown por categoria.
- `messages(conversation_id, created_at)` — histórico de chat em ordem.

## Convenções

- Toda tabela tem PK `UUID` gerada no banco (`gen_random_uuid()`, extensão
  `pgcrypto`) e colunas `created_at` / `updated_at`.
- Enums de domínio (tipo de categoria, tipo de transação, status de meta,
  papel da mensagem) são `VARCHAR` no banco e `enum` no Java — evita
  depender do `ENUM` nativo do Postgres, que é mais custoso de alterar.
