-- Reescrita da autenticação: sem senha local. Login por código de acesso
-- enviado por e-mail (6 dígitos) ou por Google. Cadastro agora inclui
-- WhatsApp e respostas de onboarding (3 perguntas fixas, opcionais).
-- Ver docs/security.md para o racional dessa decisão.

ALTER TABLE users
    ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users
    ADD COLUMN whatsapp VARCHAR(20);

ALTER TABLE users
    ADD COLUMN google_id VARCHAR(255) UNIQUE;

CREATE TABLE onboarding_answers (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    question_1   TEXT,
    question_2   TEXT,
    question_3   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE email_access_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(180) NOT NULL,
    code_hash   VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_email_access_codes_email ON email_access_codes(email);
