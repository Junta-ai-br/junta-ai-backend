CREATE TABLE USUARIOS (
    ID SERIAL PRIMARY KEY,
    NOMBRE VARCHAR(100) NOT NULL,
    APELLIDO VARCHAR(100) NOT NULL,
    EMAIL VARCHAR(100) UNIQUE NOT NULL,
    CONTRASENA VARCHAR(255) NOT NULL,
    ESTADO BOOLEAN DEFAULT TRUE,
    FECHA_REGISTRO TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);  

-- 2. Tabela de OTP (para o código de 6 dígitos)
CREATE TABLE IF NOT EXISTS otps (
    id SERIAL PRIMARY KEY,
    usuario_id INT REFERENCES usuarios(id) ON DELETE CASCADE,
    email VARCHAR(100) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    attempts INT DEFAULT 0,
    used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_otps_email_expires ON otps(email, expires_at);

-- 3. Tabela de Passkeys (para a autenticação WebAuthn)
CREATE TABLE IF NOT EXISTS passkeys (
    id SERIAL PRIMARY KEY,
    usuario_id INT REFERENCES usuarios(id) ON DELETE CASCADE,
    credential_id TEXT NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    counter BIGINT NOT NULL DEFAULT 0,
    transports VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_passkeys_usuario_id ON passkeys(usuario_id);

ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS email_verified BOOLEAN DEFAULT FALSE;