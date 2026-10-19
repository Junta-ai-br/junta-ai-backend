-- Proteção contra força bruta no código de 6 dígitos e consultas mais rápidas.

-- Quantas vezes alguém tentou adivinhar este código. Ao chegar no limite
-- (auth.access-code.max-attempts) o código é invalidado.
ALTER TABLE email_access_codes
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;

-- "Último código do e-mail" e "quantos códigos pedidos nos últimos minutos".
CREATE INDEX idx_email_access_codes_email_created
    ON email_access_codes (email, created_at DESC);

-- Limpeza periódica de códigos e refresh tokens vencidos.
CREATE INDEX idx_email_access_codes_expires ON email_access_codes (expires_at);
CREATE INDEX idx_refresh_tokens_expiry ON refresh_tokens (expiry_date);