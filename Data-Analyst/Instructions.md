# Offline AI Data Analyst — Architecture & Hard Rules

This document is the single source of truth for the project. Paste it (or attach it)
into **every** Copilot session before asking for code. It exists so Copilot never has
to "remember" a previous chat — the rules live in the file, not in conversation memory.

---

## 1. Non-Negotiable Rules

These apply to every file, every phase, no exceptions:

1. **No case-specific fixes.** If a bug is fixed, the fix must be a fix to the
   *general algorithm* (matching logic, thresholding, parsing), never a special
   branch for one dataset, one column name, or one phrasing of a query.
2. **No hardcoding, anywhere, including tests.** No hardcoded column names, domain
   words (e.g. "CIBIL", "credit score"), synonym lists, or magic query strings.
   Domain vocabulary must be *discovered at runtime* from the uploaded schema/data,
   not baked into code.
3. **No paid, licensed, or internet-downloaded dependencies at runtime.** Everything
   (models, jars) is bundled/local. Maven dependencies are resolved once at build
   time from the environment's existing repository — never fetched dynamically by
   the app itself.
4. **Domain-agnostic by default.** The system may be *tuned* with bank/fintech/credit
   exemplars, but the underlying engine (intent classifier, clause compiler, semantic
   matcher) must work identically for any tabular dataset.
5. **Deterministic-first pipeline.** The LLM is a last resort. If the deterministic
   engine + semantic/exemplar layer can answer with acceptable confidence, the LLM is
   never invoked.

---

## 2. Pipeline / Data Flow

```
User query (NL text)
      │
      ▼
[1] IntentClassifier  ──────────────► (chit_chat | data_analysis | followup | explanation | ...)
      │  embedding similarity to exemplars, WITH MARGIN CHECK (see §4.1)
      ▼
[2] SemanticEngine
      │  - resolves column/value references against the ACTUAL uploaded schema
      │  - Levenshtein + embedding similarity, margin-checked
      ▼
[3] ExemplarMatcher (archetype/template layer)
      │  - matches query structure to a known query-shape (archetype)
      │  - MUST pass margin check (§4.2) or falls through to LLM
      ▼
[4] ConceptResolver
      │  - resolves relative/fuzzy concepts ("high", "recent", "top") using
      │    actual column statistics (percentiles, date ranges) — never fixed numbers
      ▼
[5] DeterministicPlanBuilder  ◄── assembles the IR (§5) from the outputs of
      │                            [1]-[4]: intent, resolved column/value refs,
      │                            matched archetype shape, resolved concepts.
      │                            This is the ONLY component that builds an IR
      │                            from scratch on the deterministic path.
      ▼
[6] ClauseCompiler (deterministic)
      │  - builds SELECT / WHERE / GROUP BY / ORDER BY / aggregation clauses
      │    from the assembled intermediate representation (§5)
      ▼
      ├─ high confidence ──► [7] QueryExecutionService (DuckDB) ──► [8] ResultExplanationService ──► result + explanation
      │
      └─ low confidence / no match (margin check failed, §4.1/§4.2/§4.4)
                │
                ▼
         [6] LLM Fallback (Qwen2.5-3B-Instruct) — invoked immediately, no user
             prompt at this point
                │  GBNF-constrained decoding (§4.3): grammar restricts output to
                │  the flat key:value IR format AND to the top-k candidate
                │  column/value names from §4.4 — model cannot emit a column
                │  name that isn't one of the real candidates
                ▼
         flat key:value slots ──► schema/IR validation (same gate as §5)
                │
                ├─ valid ──► same ClauseCompiler ──► QueryExecutionService ──► ResultExplanationService ──► result + explanation
                │
                └─ invalid (references impossible value, IR still ambiguous)
                        │
                        ▼
                  retry LLM call with the validation error appended as
                  context (max 2 retries, §4.5)
                        │
                        └─ still invalid after retries ──► [7] user clarification
                                                            (true last resort only)
```

Key point: **the LLM never talks to the database directly.** It only ever fills in
the same intermediate representation (§5) that the deterministic path fills in. This
means a hallucinated LLM output still has to pass through the same clause compiler
and schema-validation step as a deterministic match — it cannot reference a column
or value that doesn't exist in the uploaded data. Asking the user is now the
*fallback of the fallback*, not the first thing that happens when the deterministic
layer is unsure — that's a deliberate change from the previous draft, per your
direction: go straight to the LLM, and only surface a clarification question if the
constrained LLM path itself fails validation after retries.

---

## 3. Components & Responsibilities

| Component | Responsibility | Must NOT do |
|---|---|---|
| `IntentClassifier` | Route query to chit-chat / data-analysis / followup / explanation | Use keyword lists |
| `SemanticEngine` | Map query tokens → actual column/value names via embeddings | Assume domain vocabulary |
| `ExemplarMatcher` | Match query shape to a known archetype template | Return a match below the margin threshold |
| `ConceptResolver` | Turn fuzzy terms into concrete filters using live column stats | Use fixed thresholds (e.g. "high = >700") |
| `DeterministicPlanBuilder` | Assemble the single IR (§5) from IntentClassifier + SemanticEngine + ExemplarMatcher + ConceptResolver outputs — the ONLY place an IR is built from scratch on the deterministic path | Resolve columns/values itself, apply matching logic itself — it only combines already-resolved pieces |
| `ClauseCompiler` | Deterministically assemble SQL clauses from an already-assembled, already-validated IR | Accept free-form SQL from the LLM, or build/modify an IR itself |
| `LlmFallbackService` | Fill IR slots only when 1–4 fail or are low-confidence; owns the GBNF call + validate + retry loop (§4.5) | Generate SQL directly, ask the user before its own retries are exhausted |
| `QueryExecutionService` | Execute compiled SQL against the correct DuckDB table (scoped by `sessionId` + `datasetId`) | Contain any matching/compilation logic |
| `ResultExplanationService` | Turn a query result + IR into a natural-language explanation for the user/SSE stream | Re-derive or second-guess the IR |
| `AiOrchestrator` | Coordinate the above, stream via SSE | Contain business/matching/IR-building logic itself |

---

## 4. Fixes for the Three Known Bugs

### 4.1 Intent detection failing on keyword overlap
**Root cause:** classifying by presence of words like "add", "sort" — these appear
in ordinary analysis queries too.
**Fix:** embed the query with MiniLM, compare cosine similarity against a small set
of exemplar sentences per intent class. Classify only if:
- `top1_score >= ABSOLUTE_THRESHOLD`, and
- `top1_score - top2_score >= MARGIN_THRESHOLD`

If either fails, mark intent as `ambiguous` and route to the LLM with the top-2
candidate intents as context, rather than guessing.

### 4.2 Exemplar/archetype false-positive matches
**Root cause:** best-match-only similarity search picks the closest archetype even
when it's not a good match, causing the 3B fallback model to hallucinate around a
wrong template.
**Fix:** same margin-check pattern as above, applied per archetype match. Also:
increase exemplar *diversity* (multiple paraphrases per archetype) rather than one
canonical sentence per archetype — this tightens the embedding neighborhood and
reduces accidental proximity between unrelated archetypes.

### 4.3 Small LLM (3B) failing to produce valid JSON
**Two independent, stackable fixes — implement both, pick per-call which to use:**

- **(a) Flat key:value slot format (your original idea — correct).**
  ```
  intent: aggregation
  projection: average_balance
  filter: region = "north"
  group_by: branch
  order_by: average_balance desc
  ```
  Parse with a line-based tokenizer, not a JSON parser. Trivially resilient to
  a missing brace or trailing comma, which is what kills JSON parsing on small models.

- **(b) Grammar-constrained decoding via llama.cpp GBNF.** `de.kherud:llama` exposes
  grammar-constrained sampling. Define a GBNF grammar for the flat slot format itself
  (or for JSON, if you prefer) so the model is *physically unable* to emit an invalid
  token sequence. This removes the failure mode entirely rather than just making the
  parser tolerant of it. Recommended as the primary defense; (a) as parser-level
  belt-and-braces.

### 4.4 Top-K candidate retrieval (column/value/archetype resolution)

**Use top-k, not top-1, as the retrieval step — margin-checking then happens on the
candidate *set*, not on a single winner.** This applies to all three matching points:
column/value resolution (SemanticEngine), archetype matching (ExemplarMatcher), and
intent classification (IntentClassifier).

Why this matters, concretely:
- Wide tables (50–200+ columns) make top-1-only resolution brittle: two columns can
  legitimately be close in embedding space ("net_income" vs "gross_income"), and
  picking a single winner silently is exactly how wrong-column bugs happen.
- Retrieving top-k (k = 3–5 is usually enough) gives you the margin-check input
  (top1 vs top2 *within the k*) instead of nothing to compare against.
- It gives you a genuine fallback path that isn't "guess or fail": when the margin
  check fails, **the top-k list is handed straight to the LLM fallback as a
  constrained choice set** (decided ordering — see §4.5). The model picks from k
  real column names instead of generating a column name freely, which removes an
  entire class of hallucination. Asking the user directly is not the first
  fallback; it only happens if the constrained-LLM path itself can't produce a
  valid result (§4.5).
- It's cheap: column/value embeddings are computed once at upload time and cached
  (Phase 1/2), so top-k similarity search at query time is a linear scan over a few
  hundred cached vectors — no index needed at this scale. Only reach for an ANN
  index (e.g. HNSW) if a single dataset regularly has several thousand columns,
  which is not the common case for uploaded spreadsheets.

Implementation point: `SemanticEngine.resolveColumn(query_token)` should return
`List<ScoredCandidate>` (size k), not a single `ColumnRef`. The margin check and the
"ambiguous → clarify or constrain-LLM" branching both then operate on that list, and
this same return-shape should be reused for value resolution and archetype matching
so the ambiguity-handling logic isn't duplicated three times.

### 4.5 Fallback ordering — decided: straight to constrained LLM, retry-on-invalid, user last

When the deterministic/heuristic layer fails its margin check, the system goes
**directly to the LLM fallback** — no user prompt at that point. This is the
decided behavior; disambiguation is a last resort, not a first one.

```
margin check fails
      │
      ▼
LLM fallback call, GBNF-grammar-constrained to:
  - the flat key:value IR slot format (§4.3), AND
  - the top-k candidate column/value names from §4.4 (model can only choose
    among real candidates, never invent a name)
      │
      ▼
parse output → validate against live schema (same gate ClauseCompiler already
enforces for the deterministic path)
      │
      ├─ valid  ──► proceed to ClauseCompiler ──► SQL ──► result
      │
      └─ invalid (grammar was followed structurally, but the *chosen* candidate
         still doesn't resolve, or a required slot is empty)
              │
              ▼
        retry the SAME LLM call, once more, with the validation error appended
        to the prompt as feedback (e.g. "group_by referenced 'regoin' — not in
        candidate list [region, branch, product]") — max 2 retries total
              │
              └─ still invalid after 2 retries ──► only now: surface a
                 clarification question to the user, built from the same
                 top-k candidates already retrieved (so the question is
                 concrete: "did you mean region or branch?" — not open-ended)
```

**Why this is the fix for a previously-failed attempt, not just a variant of it:**
a fallback that goes "deterministic fails → free-form LLM call → hope the JSON/text
is valid → done" has exactly the two failure modes you already hit: the model
inventing a column that doesn't exist, and the model producing an unparseable
response with no recovery path. Constraining generation to real candidates (GBNF +
top-k) removes the first; validating the output before it ever reaches SQL and
retrying with concrete feedback removes the second. The user only ever sees a
clarification question if *both* of those safety nets failed twice — which should
be rare, and when it happens the question is answerable in one tap because it's
built from the same real candidates, not a vague "please rephrase."

### 4.6 What "covering every edge case" actually means here

No threshold-and-margin system, however carefully tuned, reaches 100% correct
classification on open-ended natural language — that's true of every NLU system
regardless of budget or model size, not a gap specific to this design. Treating
"perfect, handles everything" as the target will produce false confidence, not a
better system.

The engineering target that *is* achievable, and is what this architecture is built
for, is: **every input either gets a correct answer, or gets a visible,
honest "I'm not sure — did you mean X or Y?" — never a silently wrong answer.**
That's the actual purpose of the margin checks and top-k candidates in §4.1, §4.2,
and §4.4: they don't make ambiguity go away, they make ambiguity *detectable*, so it
routes to clarification/LLM-with-constraints instead of a confident wrong guess.
Measure this system by two numbers per query category (filter, aggregation,
group+sort, followup, etc.), not by an "everything works" bar:
- **Precision on confident answers** — of the cases the system answered directly
  (no clarification), what fraction were correct? This should be very high.
- **Clarification rate** — of the rest, how often did it correctly recognize its own
  uncertainty rather than guessing? This should also be high; a clarification is a
  success condition, not a failure.

Practically: build a small regression test set per archetype category (10–20 varied
phrasings each, including deliberately ambiguous ones) and track these two numbers
as you tune thresholds — that's how you find out if a threshold change fixed one
category while quietly breaking another, instead of finding out from a user later.

---

## 5. Intermediate Representation (IR) — the Clause Compiler contract

Every path (deterministic match, exemplar match, or LLM fallback) must ultimately
produce this same structure. This is the seam that keeps the LLM sandboxed:

```
IR {
  sessionId: string
  datasetId: string          // which uploaded sheet/table within the session —
                              // a session may hold multiple sheets (Phase 1), so
                              // this must be explicit on every IR and every query
                              // request; never assume "the" table for a session
  intent: enum(data_analysis | followup | explanation | chit_chat)
  projections: [column_ref]        // what to SELECT
  filters: [ {column_ref, operator, value_or_stat_ref} ]   // WHERE
  aggregations: [ {function, column_ref, alias} ]          // SUM/AVG/COUNT...
  group_by: [column_ref]
  order_by: [ {column_ref, direction} ]
  limit: int | null
}
```

**Who builds it:** on the deterministic path, `DeterministicPlanBuilder` is the
single component that constructs this object, from the already-resolved outputs of
IntentClassifier, SemanticEngine, ExemplarMatcher, and ConceptResolver — none of
those four components build an IR themselves, they each produce one piece of it.
On the LLM path, `LlmFallbackService` fills the same structure directly from the
grammar-constrained flat-slot output (§4.3/§4.5). Either way, the IR that reaches
`ClauseCompiler` has already been validated against the live schema for the
`datasetId` it names — that's the seam that makes LLM hallucination harmless.

`column_ref` and `value_or_stat_ref` are always resolved against the **actual
uploaded schema** before the ClauseCompiler runs — this is the validation gate that
makes LLM hallucination harmless: an IR that references a non-existent column or an
impossible value is rejected before it becomes SQL, and the system re-prompts or
falls back to an explanation ("I couldn't match that to a column in your data").

---

## 6. Exemplars — sourcing note

There is no ready-made free offline library of NL→query exemplars usable here (public
academic sets like Spider are internet-downloaded and English-question-to-SQL
specific, not license-clean for redistribution, and not schema-agnostic out of the
box). The practical approach: hand-author a small set of *domain-agnostic* archetype
templates (one per SQL shape: single filter, multi-filter, aggregation, group+sort,
top-N, trend-over-time, comparison, followup-refinement), each with 4–6 paraphrases
generated offline by you/Copilot at authoring time — not fetched at runtime. Domain
tuning (bank/fintech) is layered on top as *additional* exemplars, never as
replacements for the general ones.
