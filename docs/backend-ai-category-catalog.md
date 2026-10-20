# Backend-owned category catalog

The backend supplies `financial_state.current_state.category_catalog` on initial
message processing. This uses the existing authorized snapshot object; the root
request schema and contract version remain unchanged.

Each entry has exactly `id` (UUID string), `name` (unchanged category name), and
`type` (`INCOME`, `EXPENSE`, or `BOTH`). There are no eligibility/active flags,
user IDs, timestamps, or frontend metadata. An empty owned catalog is `[]`.

Example (synthetic identity):

```json
{
  "financial_state": {
    "current_state": {
      "category_catalog": [
        {"id": "00000000-0000-4000-8000-000000000001", "name": "Synthetic category", "type": "EXPENSE"}
      ]
    }
  }
}
```

## Authority and failure behavior

`ConversationTurnStore` builds the catalog through
`CategoryService.listForUser(userId)`. Initial processing restores the owned
conversation and derives its user before building the snapshot. The frontend
message DTO supplies no catalog. Catalogs are constructed per snapshot, with no
shared cache. Lookup/mapping failures propagate and abort admission before the
AI call; they never become a fabricated catalog. Recovery of already selected
operations keeps the existing flow.

The existing execution boundary still re-queries categories for the authenticated
user and rejects missing, ambiguous, or incompatible categories. This catalog is
evidence for AI selection, never execution authorization.

## Required AI Service adapter (not implemented in this repository)

The inspected AI Service accepts this object through the existing
`FinancialStateSnapshot.current_state: dict[str, Any]`, but its
`ConversationProcessor(category_catalog=...)` provider defaults to `None`.
It will keep blocking category-dependent actions until a trusted backend adapter
is wired into the route's processor.

The adapter must read only `request.financial_state.current_state.category_catalog`,
validate the complete list and each UUID/name/type, and map the entries to the
existing internal `AvailableCategory(name, type)` (whose eligibility default is
already defined internally). Do not transmit or invent eligibility flags. Missing,
malformed, duplicate/ambiguous, or incompatible catalogs must remain fail-closed.
Never fall back to `context.metadata`, user text, or a static eligible catalog.
Preserve the existing resolver's type and ambiguity checks. Keep backend IDs on
the wire; the current internal resolver and backend execution still resolve by
name with ownership/type checks.

This path is a new integration convention to align with the AI Service team;
it does not mean that an adapter already consumes it. Trust remains at the
existing backend-to-AI service transport boundary.

## Local validation

Run only mocks and the loopback capture server (no AI agents or financial POSTs):

```powershell
.\mvnw.cmd -o '-Dtest=ConversationCategoryCatalogTest,CategoryCatalogExecutionGuardTest,CategoryServiceTest,ConversationServiceTest,AiClientTransportTest,AiClientTest' test
git diff --check
```

The transport test checks JSON category fields without logging body contents.
The AI team should validate the synthetic request with Pydantic and test the
adapter/resolver with valid, empty, invalid and incompatible catalogs, using
fakes rather than external agents. A production end-to-end claim requires that
adapter and separate homologation. Applying backend source changes to Docker
requires rebuilding its image; this task does not rebuild or restart services.
