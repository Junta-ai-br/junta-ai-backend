-- E-mails passam a ser sempre gravados e consultados em minúsculas.
-- Só altera contas que não colidem com outra já existente em minúsculas
-- (evita quebrar a restrição UNIQUE se houver duplicatas antigas).
UPDATE users u
   SET email = lower(u.email)
 WHERE u.email <> lower(u.email)
   AND NOT EXISTS (SELECT 1 FROM users o WHERE o.email = lower(u.email));

-- Códigos antigos não importam mais (vencem em minutos).
DELETE FROM email_access_codes;