# Retrofit — Phases 1-4 (already implemented)

The architecture doc changed under you while Phases 1-4 were being built (top-k
retrieval, `datasetId`, the `DeterministicPlanBuilder` component, and the
straight-to-LLM fallback ordering were all added or firmed up mid-build). This file
is a **patch pass**, not a rebuild: don't regenerate Phases 1-4 from scratch —
audit-and-patch your existing code against the checklist below, then run the
retrofit prompts for whichever items are actually missing.

Do this retrofit **before** starting Phase 5 — Phase 5 assumes `datasetId` exists
everywhere and assumes `DeterministicPlanBuilder` exists as a separate component,
both of which are new since you built Phases 1-3.

---

## Self-check first (no Copilot needed)

Go through your existing code and tick these off. Anything unticked is what you
retrofit below.

1. **`datasetId`** — does your `UploadResult` / session model assign a stable
   identifier per uploaded sheet (not just an internal table name)? Does your IR /
   query request object carry both `sessionId` and `datasetId`, or only `sessionId`?
2. **`DeterministicPlanBuilder`** — is there a component whose *only* job is
   assembling the IR from IntentClassifier + SemanticEngine + ExemplarMatcher +
   ConceptResolver outputs? Or did that merging logic end up inside `ClauseCompiler`,
   `AiOrchestrator`, or `ConceptResolver` (all three of which are explicitly
   disallowed from doing it, per the component table)?
3. **Top-k retrieval** — does `SemanticEngine.resolveColumn(...)` (and value
   resolution, and archetype matching) return a ranked list of candidates, or only
   the single best match? Top-1-only means the margin check has nothing to compare
   against and constrained-LLM candidate lists (item 5) have no source.
4. **Fallback ordering** — when the deterministic path fails its margin check, does
   your code call `LlmFallbackService` immediately, or does it surface something to
   the user first? It must be immediate.
5. **GBNF grammar scope** — does your grammar constrain the LLM to pick column/value
   names from a supplied candidate list, or only to the flat key:value *format*
   (structure valid, but the model can still invent a column name)? Both constraints
   are needed; format-only is not enough to prevent hallucinated column names.
6. **Validate-and-retry loop** — does a failed/invalid LLM output retry once or
   twice with the validation error fed back as context, or is it single-shot
   (succeed or immediately give up)?

---

## Retrofit A — `datasetId` propagation (touches Phase 1 + Phase 3's IR)

```
I'm retrofitting an existing Java 17/21 + Spring Boot 3.x project (attached:
ARCHITECTURE_AND_RULES.md, plus the relevant existing files — UploadResult,
SessionMetadata, DuckDbTableService, and the IntermediateRepresentation/IR class).

Task: add a `datasetId` field that identifies a specific uploaded sheet/table
within a session (a session may hold multiple sheets).
- If UploadResult/DuckDbTableService does not already assign a stable per-sheet
  identifier, add one (generated at upload time, stable for the life of the
  session).
- Add `datasetId` alongside the existing `sessionId` on the IR class.
- Do not change the ingestion logic itself — this is additive, not a rewrite.
- Show me every file you touch and a one-line reason for each change.
```

---

## Retrofit B — extract `DeterministicPlanBuilder` (Phase 3)

```
I'm retrofitting an existing Java project (attached: ARCHITECTURE_AND_RULES.md,
plus my existing IntentClassifier, SemanticEngine, ExemplarMatcher,
ConceptResolver, ClauseCompiler, and IR class).

Per the component table in the attached doc, ClauseCompiler must not build or merge
an IR — that responsibility belongs to a separate `DeterministicPlanBuilder`
component that does not itself exist yet.

Task:
- Find wherever the four resolved outputs (intent, resolved columns/values, matched
  archetype, resolved concepts) currently get combined into one IR — this logic is
  likely inside ClauseCompiler, ConceptResolver, or an orchestrating method.
- Extract that merging logic into a new `DeterministicPlanBuilder` class whose only
  job is assembly — it must not resolve, match, or validate anything itself.
- Update ClauseCompiler to accept an already-built IR (from
  DeterministicPlanBuilder) rather than building or merging one.
- Add a unit test asserting DeterministicPlanBuilder correctly merges four
  independently-mocked component outputs into one IR, and asserting ClauseCompiler
  no longer contains any merging/assembly logic.
- Show me every file you touch and a one-line reason for each change.
```

---

## Retrofit C — top-k retrieval (Phase 2/3)

```
I'm retrofitting an existing Java project (attached: ARCHITECTURE_AND_RULES.md,
plus my existing SemanticEngine, ExemplarMatcher, and IntentClassifier).

Task: change column/value resolution, archetype matching, and intent
classification from returning a single best match to returning a ranked top-k list
(k=3-5) of scored candidates.
- The existing margin-check logic (threshold + top1-vs-top2 margin) should now
  compare the top two entries of that list, not a single score against a stored
  runner-up value computed separately.
- Do not change the embedding model, thresholds, or exemplar sets — this is a
  return-shape change, not a re-tuning.
- Add/update unit tests to assert the returned list is ordered by score and has
  length <= k.
- Show me every file you touch and a one-line reason for each change.
```

---

## Retrofit D — fallback ordering, candidate-constrained grammar, retry loop (Phase 4)

```
I'm retrofitting an existing Java project (attached: ARCHITECTURE_AND_RULES.md,
plus my existing LlmFallbackService, GbnfGrammarProvider or grammar definition, and
whatever currently calls LlmFallbackService).

Task, three parts — implement whichever your current code doesn't already do:
1. Fallback ordering: whatever calls LlmFallbackService on a margin-check failure
   must call it immediately, with no user-facing step first. If your current code
   surfaces a clarification/question to the user before attempting the LLM call,
   remove that — clarification only happens after the LLM path (including its
   retries) fails.
2. Candidate-constrained grammar: if your GBNF grammar currently only constrains
   the flat key:value *format*, extend it to also constrain column/value slots to a
   supplied top-k candidate list per call (from Retrofit C), so the model cannot
   emit a name outside that list.
3. Validate-and-retry: if a parsed LLM output fails schema validation, retry the
   same call up to 2 additional times with the validation error appended to the
   prompt as feedback, before falling through to a "needs clarification" result.
Add/update unit tests covering: immediate invocation on margin-check failure (no
user step), a forced-invalid first response triggering exactly one retry, and
clarification only after retries are exhausted.
Show me every file you touch and a one-line reason for each change.
```

---

## After the retrofit

Re-run your existing Phase 1-4 test suite before moving on — the retrofit should
be behavior-preserving for everything that already worked, and only change the
specific gaps above. Then proceed to Phase 5 in `02_COPILOT_PROMPT_SEQUENCE.md`,
which now assumes all four retrofits are in place.
