# Stub do junta-ai-ai (para testes locais)

Este NÃO é o serviço de IA real do projeto (esse fica no repositório
`junta-ai-ai`, com CrewAI + MCP, e é responsabilidade da equipe de IA).
É apenas um substituto simples para você validar, na sua máquina, que a
integração `Backend -> IA` (classe `AiClient`) está funcionando: envio da
requisição, timeout, aplicação da ação quando a confiança é alta, etc.

## Rodar (Arch Linux)

```bash
cd tools/ai-service-stub
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```

Depois suba o backend normalmente (`mvn spring-boot:run` ou
`docker compose up`) com `AI_SERVICE_BASE_URL=http://localhost:8000`.

Teste manual com curl (depois de logar e ter um token e uma conversa):

```bash
curl -X POST http://localhost:8080/conversations/<id>/messages \
  -H "Authorization: Bearer <accessToken>" \
  -H "Content-Type: application/json" \
  -d '{"message": "gastei 80 reais no mercado"}'
```

Quando o serviço de IA real da equipe estiver pronto, basta trocar
`AI_SERVICE_BASE_URL` — nada no backend precisa mudar, contanto que o
contrato (`docs/api.md`) seja respeitado.
