IMPORTANT: THIS APPLICATION MUST SUPPORT DYNAMIC / ARBITRARY DATASETS.

The uploaded dataset is NOT fixed.

The dataset used during development/testing is only one example. Users may upload completely different Excel/CSV datasets with different:
- table names
- column names
- column types
- column counts
- semantic concepts
- aliases
- boolean representations
- numeric fields
- PII/governance metadata

Therefore the application MUST NOT contain dataset-specific knowledge in source code.

==================================================
ABSOLUTE NO-HARDCODING REQUIREMENT
==================================================

Do NOT create or introduce any code that hardcodes the current dataset's:

- column names
- aliases
- semantic mappings
- expected values
- boolean values
- dataset name
- table name
- relationships between columns
- PII column names
- encryption column names
- RBAC column names
- score column names
- test-query phrases
- natural-language phrases
- example-specific mappings

Examples such as:

"encrypted" -> "encrypted_y_n"
"rbac" -> "rbac_y_n"
"v3" -> "v3_score"

MUST NOT be implemented as static mappings, dictionaries, enums, constants,
if/else branches, switch cases, configuration entries, or dataset-specific classes.

The examples above are ONLY behavioral examples used to explain the bugs.

They are NOT implementation requirements.

The solution MUST work when the dataset is completely replaced.

For example, if the current dataset is replaced with another dataset whose
columns have completely different names, the same application architecture
must continue to function without modifying Java source code.

==================================================
RUNTIME SCHEMA IS THE SOURCE OF TRUTH
==================================================

Column identity, datatype, available columns, and dataset-specific metadata
must be obtained from the currently active dataset at runtime.

Use the application's existing schema/catalog/TableSchema infrastructure
where available.

Do not create a parallel static semantic schema for the current dataset.

If additional metadata is genuinely required for semantic resolution,
design a generic metadata model that is populated from runtime schema
information and/or existing dataset metadata mechanisms.

The application code must not know in advance what columns exist.

==================================================
SEMANTIC RESOLUTION REQUIREMENT
==================================================

Do not solve semantic resolution through raw string containment alone.

The system should conceptually separate:

1. Candidate retrieval
2. Candidate ranking
3. Semantic column resolution
4. Value normalization
5. Operation extraction
6. Query-shape determination
7. Query-plan construction
8. SQL compilation

Retrieval may produce multiple candidates.

Resolution must determine the correct candidate using available runtime
schema information, semantic signals, datatype compatibility, query context,
and other generic evidence already available in the application.

Do not allow downstream components to independently reinterpret the original
utterance after a semantic column has already been resolved.

Once a column is resolved, preserve its identity through the remainder of
the pipeline.

==================================================
BOOLEAN SEMANTIC NORMALIZATION
==================================================

Boolean concepts must be handled generically.

The system should distinguish:

- semantic concept
- referenced column
- requested boolean state
- physical representation stored by the dataset

For example, a dataset may represent boolean state using:

Y/N
YES/NO
true/false
1/0
enabled/disabled
other representations

Do not hardcode the representation of the current dataset.

The normalization mechanism must be driven by runtime column metadata,
observed schema/value information, or an existing generic metadata mechanism.

Most importantly:

FIRST resolve WHICH COLUMN the user is referring to.

THEN normalize the requested semantic boolean value for that column.

Never match a value such as "Y" independently against every low-cardinality
column in the dataset.

==================================================
MULTI-FILTER / MULTI-COMPARISON REQUIREMENT
==================================================

The query representation must support multiple independent predicates.

Do not design extraction around:

"find the first comparison"

or:

"find the nearest numeric column"

and stop.

Represent comparisons structurally.

Conceptually:

Predicate
    column
    operator
    value

and:

FilterExpression
    predicate(s)
    logical operator(s)

The implementation should support arbitrary numbers of compatible predicates
without adding special cases for v3/v4 or any other current columns.

==================================================
COLUMN ALIAS / SEMANTIC MATCHING
==================================================

Column aliases must NOT be implemented as a hardcoded dictionary for the
current dataset.

Instead, design column resolution so that natural language can be matched
against runtime schema information using the application's available semantic
signals.

The mechanism should support equivalent expressions such as:

short name
display name
natural-language description
column name
token variations
semantic similarity
datatype compatibility
query context

where such information is actually available.

Do not assume every column has a manually maintained alias list.

==================================================
PROJECTION / COLUMN SELECTION
==================================================

Projection selection must use the same generic semantic column-resolution
mechanism.

Do not implement special cases for:

"encryption column"
"PII column"
"score column"
or any current test phrase.

The requested projection should resolve against the active runtime schema.

==================================================
QUERY SHAPE
==================================================

QueryShapeClassifier must not depend solely on lexical/exemplar similarity.

Inspect whether query shape can be determined from the structured semantic
representation.

The goal is to distinguish operations such as:

ROW_LIST
AGGREGATE
GROUPED_AGGREGATE
TOP_N
DISCOVERY
SORT
etc.

based on the actual semantic structure rather than memorized phrases.

Do not add phrase-specific classification rules just for the reported examples.

==================================================
DETERMINISTIC OPERATIONS
==================================================

Operations whose semantics are deterministic should have deterministic
planning paths.

This includes operations such as:

- filtering
- multiple predicates
- projection
- sorting
- TOP-N / LIMIT
- follow-up mutations when sufficient previous state exists

Do not send deterministic operations to the LLM simply because a dedicated
deterministic resolver is currently missing.

Do not solve this by adding phrase-specific checks.

==================================================
TOP-N
==================================================

TOP-N must not require an invented ranking measure when the user has not
specified one.

Do not invent:

COUNT(some_current_dataset_column)

or:

GROUP BY every column

merely to make "top N" compile.

TOP-N semantics must be represented explicitly in the query model.

If the query does not provide enough information to determine a ranking
semantically, represent that ambiguity explicitly rather than inventing
dataset-specific logic.

The implementation must work with arbitrary datasets.

==================================================
FOLLOW-UP QUERIES
==================================================

Follow-up processing should operate against the previous query/session
semantic state whenever possible.

Examples:

"sort it in descending order"
"top 20"
"show only that column"

must be interpreted relative to the previous state when the required context
exists.

Do not require the entire meaning to be rediscovered from the follow-up
utterance alone.

Do not make FOLLOW_UP success depend solely on a brittle intent-classification
label if conversation state already provides stronger evidence.

Follow-up operations should be modeled as transformations/mutations of the
previous semantic/query state.

==================================================
SCHEMA / META QUERIES
==================================================

Investigate why schema/meta requests are being routed through ordinary
row-filtering logic.

Do not add a special case for the current PII example.

Establish a generic distinction between:

- data operations
- schema/meta operations
- governance/metadata operations

based on semantic intent and runtime schema context.

==================================================
LLM ROLE
==================================================

The LLM is a fallback reasoning/planning component.

It must NOT become a replacement for deterministic application logic.

Do not use the LLM to compensate for missing deterministic abstractions.

Do not add prompts that simply memorize the current dataset or current
examples.

LLM output must be validated against runtime schema and the application's
supported semantic/query model.

==================================================
ARCHITECTURAL RULE
==================================================

Before changing code, identify where semantic information is currently lost.

Do not patch the failure downstream if the actual information was lost
upstream.

For example:

If SchemaRetriever produces the correct candidate but SlotInferenceEngine
loses the referenced column identity, fix the semantic representation or
resolution boundary rather than adding another condition inside SQL generation.

If QueryShapeClassifier misclassifies because the semantic representation
does not contain enough structured information, improve the representation
or classification boundary rather than adding another keyword.

==================================================
DYNAMIC DATASET TEST
==================================================

Before considering the implementation complete, reason about at least three
hypothetical datasets with completely different schemas.

The code should require NO source-code changes when switching between them.

Example conceptually:

Dataset A:
different column naming conventions

Dataset B:
different boolean representation

Dataset C:
different numeric fields and semantic concepts

These are conceptual validation scenarios only.

DO NOT encode these datasets into the code.

==================================================
IMPLEMENTATION PROCESS
==================================================

PHASE 1 — READ ONLY AUDIT

Do not modify code yet.

Inspect:

- package structure
- QueryOrchestrator
- IntentClassifier
- QueryShapeClassifier
- SchemaRetriever
- SlotInferenceEngine
- FollowUpResolver
- QueryPlan model
- PlanGenerator
- PlanValidator
- SqlCompiler
- SessionStore
- TableSchema/catalog models
- relevant services/interfaces/tests

Trace the complete data flow.

PHASE 2 — ROOT CAUSE REPORT

For each reported problem identify:

1. Current behavior
2. Actual root cause
3. Architectural boundary where information is lost
4. Existing component responsible
5. Correct responsibility
6. Proposed generic solution
7. Why it works with arbitrary datasets
8. How it avoids hardcoding
9. Tests required

Do not implement fixes during this phase.

PHASE 3 — ARCHITECTURAL IMPLEMENTATION

Implement the smallest coherent architectural changes.

Prefer extending existing abstractions over creating duplicate systems.

Do not rewrite working components without evidence.

Do not introduce dataset-specific classes.

Do not introduce test-specific branches.

Do not introduce hardcoded mappings.

PHASE 4 — REGRESSION VALIDATION

Verify:

- single filter
- multiple filters
- multiple numeric comparisons
- boolean filters
- projection
- semantic column resolution
- aliases
- sort
- TOP-N
- follow-up mutations
- schema/meta queries
- LLM fallback
- arbitrary/dynamic datasets

The reported examples should be treated as regression tests, NOT as
implementation rules.

A fix is acceptable only if it solves the underlying general problem rather
than making the listed examples pass through special handling.

==================================================
FINAL CONSTRAINT
==================================================

If you find yourself about to write:

if (query.contains(...))
if (column.equals(...))
switch(columnName)
Map<String, String> datasetAliases
hardcoded dataset metadata
hardcoded current column mappings
hardcoded test values

STOP.

Do not implement that approach.

Instead, identify what generic runtime information, semantic representation,
resolver, metadata model, or architectural boundary is missing and solve
the problem there.
