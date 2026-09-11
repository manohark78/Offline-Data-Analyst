Implement the generic semantic column-resolution fix described below.

IMPORTANT:
Do not create a dataset-specific solution.
Do not hardcode any current dataset column name, alias, query phrase, or value.

The uploaded dataset is dynamic and can be completely different in the future.

==================================================
PROBLEM
==================================================

The current semantic pipeline retrieves candidate columns for a user concept,
but the final column resolution is not reliably deciding whether the result is:

1. uniquely resolvable
2. ambiguous
3. unresolved

This causes two opposite problems:

- When exactly one semantically compatible runtime column exists, the system may
  fail to resolve it or may unnecessarily ask for clarification.

- When multiple columns are plausible, the system may arbitrarily select one
  candidate, which is unsafe.

Example:

Runtime schema contains only one semantically compatible name-related column.

User:
"display name"

Expected:
Resolve that unique compatible runtime column automatically.

There is NO requirement to hardcode the physical column name.

Another runtime schema could contain:

first_name
last_name
full_name
customer_name

User:
"display name"

Expected:
Do NOT arbitrarily select one.
Preserve ambiguity and request clarification if available semantic/contextual
evidence cannot safely distinguish them.

==================================================
REQUIRED SOLUTION
==================================================

Fix the existing semantic column-resolution mechanism.

The solution must establish this generic decision process:

User concept
    ↓
Candidate retrieval
    ↓
Candidate evaluation
    ↓
Semantic/contextual scoring
    ↓
Resolution decision
       ├── UNIQUE + COMPATIBLE → RESOLVE
       ├── MULTIPLE PLAUSIBLE  → AMBIGUOUS
       └── NONE COMPATIBLE     → UNRESOLVED

Do NOT simply return the first candidate.

Do NOT blindly return the highest-ranked candidate.

The resolver must evaluate the candidate set and determine whether the evidence
is strong enough to safely resolve the requested semantic concept.

==================================================
RUNTIME SCHEMA
==================================================

The active runtime schema is the source of truth.

Use the existing schema/catalog/TableSchema infrastructure.

Column resolution must work against whatever columns exist in the currently
loaded dataset.

Do not introduce:

- dataset-specific classes
- dataset-specific configuration
- hardcoded column mappings
- hardcoded aliases
- current-dataset semantic dictionaries
- query-specific if/else conditions

The production code must not contain knowledge of the current test dataset.

==================================================
CANDIDATE EVALUATION
==================================================

Use the existing semantic signals available in the project.

Depending on the current architecture, these may include:

- normalized column name
- column metadata
- datatype compatibility
- embedding similarity
- semantic exemplar similarity
- lexical relevance
- query context
- operation context
- candidate score
- confidence
- score margin between candidates

Do not invent a completely separate semantic-resolution system if the existing
components can be extended cleanly.

Prefer modifying/extending the existing SchemaRetriever / semantic resolver /
ExemplarMatcher infrastructure where appropriate.

Candidate retrieval and final resolution should remain separate responsibilities.

SchemaRetriever should retrieve/rank candidates.

The semantic resolver should make the final decision.

==================================================
UNIQUE CANDIDATE BEHAVIOR
==================================================

If the runtime schema contains exactly one candidate that is semantically
compatible with the requested concept and satisfies the relevant contextual
and datatype constraints:

Resolve it automatically.

Do NOT ask the user for clarification merely because the candidate was not
explicitly named by the user.

Do NOT require an exact lexical match.

The fact that the candidate is unique is valid evidence, but uniqueness alone
must not override semantic incompatibility.

Conceptually:

unique + semantically compatible
        → resolved

==================================================
AMBIGUITY BEHAVIOR
==================================================

If multiple candidates are semantically plausible:

Do not arbitrarily select one.

Use the available semantic/contextual evidence to determine whether one
candidate is sufficiently stronger than the others.

If the evidence is insufficient to distinguish them:

Return an explicit ambiguous-resolution result.

The existing application should then follow its clarification/error contract.

Do not silently choose a column merely because:

- it was returned first
- it has the same datatype
- it has the highest tiny score difference
- its name contains the query word

==================================================
UNRESOLVED BEHAVIOR
==================================================

If no candidate is semantically compatible:

Return an explicit unresolved result.

Do not:

- invent a column
- select an unrelated column
- fall back to an arbitrary candidate
- ask the LLM to invent a physical column name

The existing pipeline should handle the unresolved state according to its
current contract.

==================================================
PRESERVE SEMANTIC IDENTITY
==================================================

Once a column has been resolved, preserve its typed/runtime semantic identity
through downstream processing.

Do not convert the resolved column back into raw natural-language text.

Downstream components such as:

- SlotInferenceEngine
- QueryPlan
- filtering
- projection
- aggregation
- sorting
- SQL compilation

should consume the resolved identity directly.

Do not make downstream components rediscover the same column.

==================================================
CONFIDENCE / DECISION POLICY
==================================================

Do not introduce arbitrary magic thresholds merely to make the current tests
pass.

First inspect how confidence/similarity is currently represented.

If thresholds are required, define them as a generic resolution policy based
on candidate scores and score separation, and make the behavior explainable.

The policy must distinguish:

- strong unique match
- close competing matches
- no meaningful match

Avoid a rule such as:

"if top score > X then always select it"

without considering competing candidates.

A top candidate that is only marginally better than another plausible candidate
should remain ambiguous.

==================================================
NO LLM FOR THIS BASIC DECISION
==================================================

Do not use the local LLM merely to decide between runtime schema candidates
when deterministic semantic evidence is already sufficient.

The goal is to make common/simple resolution CPU-efficient and deterministic.

LLM fallback may remain available for genuinely complex semantic reasoning,
but it must not invent or bypass runtime schema validation.

==================================================
TESTS
==================================================

Add or update tests for generic behavior.

Test 1 — Unique candidate

Runtime schema:
one semantically compatible candidate.

User concept:
a natural-language reference to that concept.

Expected:
automatic resolution.

Test 2 — Multiple candidates

Runtime schema:
multiple semantically plausible candidates.

User concept:
ambiguous reference.

Expected:
no arbitrary selection.
Return ambiguity when evidence cannot distinguish candidates.

Test 3 — Strong contextual disambiguation

Runtime schema:
multiple candidates.

Query context clearly favors one candidate.

Expected:
resolve the correct candidate using generic semantic/contextual evidence.

Test 4 — No candidate

Runtime schema:
no compatible column.

Expected:
unresolved result.

Test 5 — Paraphrased language

Use multiple different natural-language expressions referring to the same
semantic concept.

Expected:
the same runtime column can be resolved without adding individual
hardcoded phrases.

Test 6 — Completely different dataset

Use a schema whose column names are completely different from the current
development dataset.

Expected:
same resolver works without source-code changes.

Test 7 — Downstream propagation

After resolution, verify that the resolved column identity reaches the
QueryPlan and SQL compiler without being reinterpreted from raw text.

==================================================
REGRESSION REQUIREMENT
==================================================

Run the existing test suite.

Run all semantic-resolution tests.

Run relevant integration/end-to-end tests.

The reported current example is a regression test only.

Do not implement logic specifically for that example.

==================================================
ANTI-HARDCODING CHECK
==================================================

Before finishing, inspect all changed files.

The implementation must NOT contain:

if (columnName.equals("currentColumn"))
if (query.contains("currentPhrase"))
switch (columnName)
Map<String, String> currentDatasetAliases
hardcoded current dataset metadata
special handling for the reported example

If such logic appears necessary, stop and redesign the solution around the
generic runtime-schema/semantic-resolution mechanism.

==================================================
IMPLEMENTATION CONSTRAINT
==================================================

Make the smallest coherent change necessary.

Do not rewrite unrelated components.

Do not replace the current architecture unnecessarily.

Do not introduce duplicate semantic-resolution pipelines.

Reuse existing abstractions where they are correct.

After implementation, provide a concise report containing:

1. Root cause
2. Components changed
3. How unique/ambiguous/unresolved resolution now works
4. How runtime schema is used
5. How hardcoding was avoided
6. Tests added/updated
7. Existing tests/results
8. Any remaining limitations
