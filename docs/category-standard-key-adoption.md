# Standard category presentation identity

## Contract

Category REST responses add nullable, read-only `standardKey`. Existing fields
and POST/PUT requests remain unchanged (`name`, `type`). Clients cannot assign,
change or clear a key. A null key means unclassified, not proof of custom origin.
The backend-to-AI snapshot remains exactly `id`, `name`, `type`.

V13 adds a nullable column, allowed-key CHECK, per-owner partial unique index and
an UPDATE trigger. It does not update any rows, reset initialization flags or
recreate deleted categories. The trigger permits NULL -> known key only for a
separately approved adoption; once assigned, a key cannot be cleared or replaced.
The application does not execute adoption. Name/type edits and current deletion
rules remain unchanged. Ordinary POST recreation always has a null key.

## Inventory procedure — prepared, NOT executed on existing environments

1. Obtain explicit authorization for the target environment. Verify database,
   Flyway version, rollback/backup and permissions. Do not use production defaults.
2. Run only aggregate, read-only inventory:

```sql
BEGIN TRANSACTION READ ONLY;
SELECT count(*) AS categories,
       count(*) FILTER (WHERE standard_key IS NULL) AS unclassified
FROM categories;
SELECT standard_key, count(*) FROM categories
WHERE standard_key IS NOT NULL GROUP BY standard_key;
SELECT count(*) AS owners_with_unclassified FROM
  (SELECT user_id FROM categories WHERE standard_key IS NULL GROUP BY user_id) s;
COMMIT;
```

3. An authorized reviewer prepares a non-versioned manifest of explicit
   user_id/category_id -> key assignments, with evidence or explicit adoption
   approval and the expected current metadata/revision. Never infer origin solely
   from name, type, creation time or `initial_categories_initialized`. A custom
   homonym must not be silently promoted. Renamed/deleted/ambiguous rows stay null.
4. Dry-run the manifest validation without writes: UUID ownership, known key,
   null current value (or the same already-adopted key for an idempotent retry),
   uniqueness per owner and absence of concurrent revision changes. Report counts,
   not financial payloads or account identities in shared logs.
5. Any later write requires separate explicit authorization. In a transaction,
   lock the user using the same owner lock as CRUD, recheck every guard, and set
   only the metadata for the approved UUID. Never rename, retype, replace UUID,
   move transactions, reset initialization or recreate rows. Divergence aborts
   the batch; do not invent a fallback assignment. Retain an authorized audit trail.
   No executable write/backfill script is included in this change.

## Rollout and rollback

Test V12 -> V13 first on disposable PostgreSQL with existing synthetic rows and
financial relationships. Apply additive schema before new backend; an old backend
can keep working, creating new unclassified rows. Deploy the frontend with nullable
and unknown-key UUID fallback. No simultaneous AI rollout is required.
Rollback application versions while retaining additive schema and key values;
never erase category rows or drop keys automatically. Immutability intentionally
prevents clearing/reassigning keys, so manifest approval must precede adoption.

## Acceptance limits

New empty accounts receive all 14 distinct keys. Existing accounts and preexisting
homonyms are preserved without classification. Guaranteeing all 14 fixed colors
for old accounts requires the separately reviewed adoption procedure. Custom
categories retain deterministic UUID fallback; a finite palette cannot guarantee
unlimited distinct colors. Standard keys are presentation-only, including after a
user edits the name or financial type.
