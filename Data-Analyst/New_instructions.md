# Copilot Build Sequence — Prompt by Prompt

How to use this file:
- Start a **fresh Copilot session per phase** (don't rely on chat memory).
- At the start of each session, attach/paste `01_ARCHITECTURE_AND_RULES.md` plus
  your existing `pom.xml`, `application.yml`, and (for frontend phases) your HTML/CSS/JS.
- Paste the phase prompt as-is. Each phase targets 8–10 files, inside Copilot's
  practical multi-file working-set limit.
- After each phase: compile, run existing tests, commit, *then* move to the next phase.
  Don't chain phases in one session.

---

## Phase 0 — Context Primer (paste first, every session)

```
You are working on a Java 17/21 + Spring Boot 3.x offline, privacy-first natural
language data analyst. Read the attached ARCHITECTURE_AND_RULES.md fully before
writing any code — it contains non-negotiable rules (no hardcoding, no case-specific
fixes, no internet/paid dependencies) and the exact pipeline and IR contract you must
implement against. Do not deviate from the component responsibilities table. If a
requirement conflicts with a rule in that file, point out the conflict instead of
silently choosing one.
```

---

## Phase 1 — Ingestion & Schema Layer (~8 files)

```
Implement the data ingestion layer per ARCHITECTURE_AND_RULES.md.
Files needed:
- FileIngestionService (CSV via Commons CSV / Apache Tika, Excel via Apache POI,
  multi-sheet support with conflict resolution when sheet names collide)
- SchemaProfiler: infers column types, computes per-column statistics (min/max,
  percentiles for numeric, distinct-value cardinality for categorical, date ranges
  for temporal) at upload time — this feeds ConceptResolver later, so statistics
  must be real, not placeholders
- DuckDbTableService: creates/loads a DuckDB table per uploaded sheet, session-scoped
- SessionManager: UUID-based session with DuckDB persistence so sessions survive
  restart
- DTOs: UploadResult, ColumnProfile, SessionMetadata
- Unit tests for SchemaProfiler statistics correctness (use synthetic in-memory data,
  not hardcoded to any real dataset's column names)
Do not implement query parsing yet — that's Phase 2/3.
```

---

## Phase 2 — Embeddings & Semantic Engine (~8 files)

```
Implement the semantic layer per ARCHITECTURE_AND_RULES.md §3–4.1.
Files needed:
- OnnxEmbeddingService: loads all-MiniLM-L6-v2 ONNX model, produces 384-dim vectors,
  batches requests
- EmbeddingCache: avoids re-embedding identical strings within a session
- SemanticEngine: at upload time, embeds column names + sample categorical values;
  at query time, resolves query tokens to actual column/value names via cosine
  similarity + Levenshtein fallback, WITH the margin-check rule from §4.1/4.2
  (absolute threshold AND top1-top2 margin — implement both as configurable
  constants, not magic numbers buried in logic)
- IntentClassifier: classifies chit_chat / data_analysis / followup / explanation
  using embedding similarity against a small exemplar set (create the exemplar set
  as a resource file, domain-agnostic, per §6 of the architecture doc), same
  margin-check pattern
- Unit tests proving the margin check actually rejects an ambiguous case (construct
  two synthetic exemplars close together in meaning and assert `ambiguous` is
  returned, not a forced best-guess)
```

---

## Phase 3 — Exemplar Matching & Clause Compiler (~9 files)

```
Implement the deterministic matching + compilation layer per ARCHITECTURE_AND_RULES.md
§3–§5.
Files needed:
- ExemplarMatcher: matches resolved query against archetype templates using
  SemanticEngine, margin-checked (§4.2)
- ConceptResolver: converts fuzzy terms ("high", "recent", "top N") into concrete
  filter values using the ColumnProfile statistics from Phase 1 — never fixed
  numeric thresholds
- IntermediateRepresentation (IR): the exact structure from §5 — projections,
  filters, aggregations, group_by, order_by, limit
- ClauseCompiler: builds SELECT/WHERE/GROUP BY/ORDER BY SQL fragments from a
  validated IR, validating every column_ref against the live schema before emitting
  SQL (this is the sandboxing gate mentioned in §2 — reject anything referencing a
  nonexistent column)
- Archetype resource file: 8–10 domain-agnostic query-shape templates (single
  filter, multi-filter, aggregation, group+sort, top-N, trend-over-time, comparison,
  followup-refinement), each with 4–6 paraphrase exemplars
- Unit tests: one per archetype shape, plus a test asserting an IR referencing an
  invalid column is rejected before reaching SQL
```

---

## Phase 4 — LLM Fallback (~8 files)

```
Implement the LLM fallback per ARCHITECTURE_AND_RULES.md §4.3 and §4.5 (decided
fallback ordering), using Qwen2.5-3B-Instruct via the llama.cpp Java bindings
(de.kherud:llama).
Files needed:
- LlamaModelService: loads/manages the local Qwen2.5-3B-Instruct model, no network
  calls
- GbnfGrammarProvider: defines a GBNF grammar constraining output to BOTH the flat
  key:value slot format from §4.3 AND the top-k candidate column/value names passed
  in for this call (§4.4) — the model must be structurally unable to emit a slot
  format violation OR a column/value name outside the supplied candidate list
- FlatSlotParser: line-based parser for the key:value format, used to turn the
  grammar-constrained output into the IR object — must not use a JSON parser
- IrValidator: validates a parsed IR against the live schema (same gate the
  deterministic ClauseCompiler path uses) — returns either "valid" or a structured
  validation error describing exactly which slot/value failed and why
- LlmFallbackService: invoked immediately (no user prompt) whenever
  IntentClassifier/ExemplarMatcher/SemanticEngine fail their margin check (§4.1,
  §4.2, §4.4); calls LlamaModelService with GBNF grammar + top-k candidates; on
  IrValidator failure, retries the SAME call up to 2 times with the validation
  error appended to the prompt as feedback per §4.5; only after retries are
  exhausted does it return a "needs clarification" result (built from the same
  top-k candidates) instead of an IR — it does NOT ask the user before attempting
  the constrained LLM call
- Unit tests: FlatSlotParser handles missing fields, extra whitespace, and
  out-of-order lines correctly; LlmFallbackService test asserting a forced-invalid
  first response triggers exactly one retry-with-feedback before either succeeding
  or falling through to clarification after 2 retries
```

---

## Phase 5 — Orchestration & Streaming (~7 files)

```
Implement the orchestration/streaming layer per ARCHITECTURE_AND_RULES.md §2.
Files needed:
- AiOrchestrator: wires IntentClassifier → SemanticEngine → ExemplarMatcher →
  ConceptResolver → ClauseCompiler; on any margin-check failure, calls
  LlmFallbackService directly (no user-facing step in between, per §4.5); only
  surfaces a clarification response to the user if LlmFallbackService itself
  returns "needs clarification" after its internal retries — contains NO
  matching/business logic itself, only coordination
- SseStreamController: Spring SSE endpoint streaming partial results/explanation
  tokens to the frontend
- QueryController: REST endpoint accepting a query + sessionId, delegating to
  AiOrchestrator
- ExportService: CSV/Excel export of a result set via Apache POI
- Integration test: end-to-end query through AiOrchestrator against an in-memory
  DuckDB table, covering both the deterministic path and a forced-ambiguous path
  that reaches LlmFallbackService
```

---

## Phase 6 — Conversation Persistence (~6 files)

```
Implement conversation/session persistence per ARCHITECTURE_AND_RULES.md.
Files needed:
- ConversationHistoryService: stores messages + query results per session in DuckDB,
  persisted across application restarts
- ConversationRepository: DuckDB-backed CRUD for conversation entries
- FollowupContextResolver: uses IntentClassifier's `followup` intent + prior IR from
  ConversationHistoryService to resolve pronouns/implicit references ("now sort by
  that", "add region too") into a new IR — reuses ClauseCompiler, does not
  special-case any specific follow-up phrase
- DTOs for conversation entry / history response
- Unit tests: a two-turn conversation where turn 2 is a followup, asserting the
  resolved IR carries over the correct prior filters
```

---

## Phase 7 — Frontend Wiring (only if HTML/JS need updates)

```
Wire the existing vanilla HTML/CSS/JS frontend (attached) to the Phase 5 endpoints:
SSE streaming, multi-sheet upload, conversation history sidebar, light/dark toggle,
data preview, copy/export buttons. Do not introduce any framework or build step —
plain fetch/EventSource only, matching the existing file structure.
```

---

## Notes on chunk sizing
GitHub Copilot's multi-file edit/working-set has historically had a working ceiling
around 10 files per pass. The phases above are sized to 6–9 generated files each,
leaving headroom for Copilot to also touch a config or test-resources file without
hitting that ceiling. If a phase still fails partway, split it at the natural
sub-boundary already shown (e.g. Phase 2 → "embedding service" vs "intent classifier"
as two separate prompts) rather than shrinking file count arbitrarily.
