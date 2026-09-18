"""
Stub do serviço de IA (junta-ai-ai) — SOMENTE para testar localmente a
integração Backend <-> IA enquanto o repositório real da equipe de IA não
está disponível. Implementa o contrato descrito em docs/api.md do backend.

Não é CrewAI real: apenas simula respostas para você validar que o
AiClient do Spring Boot está chamando, tratando timeout e aplicando (ou
não) a ação sugerida corretamente.

Rodar:
    pip install fastapi uvicorn --break-system-packages   # (ou use um venv)
    uvicorn main:app --reload --port 8000
"""

from datetime import date
from typing import Optional, List

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="junta-ai-ai (stub de testes)")


class CategoryContext(BaseModel):
    id: str
    name: str
    type: str


class ChatRequest(BaseModel):
    userId: str
    conversationId: str
    message: str
    availableCategories: List[CategoryContext] = []


class AiAction(BaseModel):
    intent: str
    categoryName: str
    transactionType: str
    description: Optional[str] = None
    amount: float
    transactionDate: date
    confidence: float


class ChatResponse(BaseModel):
    reply: str
    action: Optional[AiAction] = None


@app.post("/ai/chat", response_model=ChatResponse)
def chat(request: ChatRequest) -> ChatResponse:
    text = request.message.lower()

    # Heurística bem simples só para demonstrar o contrato — a IA real
    # (CrewAI + MCP) vai substituir isso por interpretação de linguagem natural.
    if "gastei" in text or "paguei" in text:
        categoria = request.availableCategories[0].name if request.availableCategories else "Outros"
        return ChatResponse(
            reply=f"Anotei essa despesa em {categoria}.",
            action=AiAction(
                intent="CREATE_TRANSACTION",
                categoryName=categoria,
                transactionType="EXPENSE",
                description=request.message,
                amount=1.0,  # stub: valor fixo só para teste manual
                transactionDate=date.today(),
                confidence=0.9,
            ),
        )

    return ChatResponse(reply="Não identifiquei nenhuma transação nessa mensagem. Pode detalhar?", action=None)


@app.get("/health")
def health():
    return {"status": "ok"}
