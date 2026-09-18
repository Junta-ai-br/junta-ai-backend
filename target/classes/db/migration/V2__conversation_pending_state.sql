-- Persistência do estado conversacional entre turnos.
-- O serviço de IA (junta-ai-ai) é stateless: cada chamada precisa receber
-- de volta o pending_operation/pending_action da rodada anterior. O Backend
-- é quem guarda esse estado (ver specs/001-ai-service-foundation/contracts
-- do repositório junta-ai-ai — "The AI Service has no database operation
-- or persistence contract").
ALTER TABLE conversations
    ADD COLUMN pending_state TEXT;
