# DataDesk – Production Architecture

> Fully offline • Deterministic-first • Validated • LLM fallback • Conversational • Secure

## 1. Core Architecture

```text
User
  |
  v
Web UI (CSV/Excel upload, chat, results, export)
  |
  v
Spring Boot Backend
  |
  +--> Dataset Ingestion
  |      CSV/Excel -> DuckDB relations -> Schema/Samples -> Dataset READY
  |
  +--> AnalyzeMessageService
         |
         v
    Reference & Context Analysis
         |
         v
    Structural Semantic Extraction
         |
         v
    Evidence Generation
       |-- MiniLM embeddings
       |-- Structured exemplars
       |-- Schema evidence
       `-- Optional Cross-Encoder
         |
         v
    Evidence Fusion + Candidate Ranking
         |
         v
    Semantic Resolver
       |
       +--> RESOLVED --------------------+
       |                                  |
       `--> AMBIGUOUS / INSUFFICIENT      |
                    |                     |
                    v                     |
             Local Qwen 2.5 3B           |
             fallback only               |
                    |                     |
                    v                     |
             Semantic IR only            |
                    |                     |
                    +----------+----------+
                               |
                               v
                        Validation Gates
                               |
                               v
                         Typed QueryPlan
                               |
                               v
                       Typed SQL Compilers
                               |
                               v
                       SQL / Resource Safety
                               |
                               v
                         Parameterized DuckDB
                               |
                               v
                         Result Validation
                               |
                               v
                         UI / Export / State
```

## 2. Fundamental Rule

DataDesk is **not**:

```text
Natural language -> LLM -> SQL -> DuckDB
```

It is:

```text
Natural language
 -> semantic evidence
 -> schema grounding
 -> semantic resolution
 -> typed QueryPlan
 -> validation
 -> typed SQL compilation
 -> controlled DuckDB execution
```

Qwen is a **fallback semantic interpreter**, never the execution authority.

## 3. Dataset Lifecycle

```text
NO_DATASET
   -> UPLOADING
   -> PROCESSING
   -> READY

PROCESSING -> FAILED
```

The chat UI should be enabled only in `READY`. The backend must enforce the same rule.

## 4. Semantic Pipeline

### Reference & Context

Resolve structured references against conversation state. Do not implement follow-up detection with hardcoded keyword checks.

### Structural Evidence

Extract evidence for:

- projection
- aggregation
- filters
- group by
- having
- ordering
- limit
- numeric constraints
- candidate fields

This layer produces evidence, not SQL.

### MiniLM

`all-MiniLM-L6-v2` via local ONNX Runtime:

```text
Tokenizer
 -> input_ids / attention_mask / token_type_ids
 -> ONNX inference
 -> mean pooling
 -> L2 normalization
 -> float[384]
```

Use it for semantic/exemplar retrieval and schema-description retrieval. Similarity is evidence, not authority.

### Evidence Fusion

```text
Structural evidence
+ MiniLM similarity
+ exemplar evidence
+ schema evidence
+ context evidence
+ type compatibility
        |
        v
Ranked candidates
```

Possible outcomes:

- `RESOLVED`
- `AMBIGUOUS`
- `INSUFFICIENT`
- `INVALID`

Do not force a guess when evidence conflicts.

## 5. Qwen Fallback

Use local Qwen 2.5 3B only when the deterministic/evidence pipeline cannot confidently resolve the request.

```text
Semantic Resolver
   |-- RESOLVED -> QueryPlan
   `-- AMBIGUOUS/INSUFFICIENT
           -> Qwen
           -> Semantic IR
           -> same validators
           -> QueryPlan
```

Qwen must **never generate executable SQL directly**.

Prefer GBNF/structured decoding only after verifying that the actual llama.cpp Java binding/build supports it. Otherwise use a strict local text protocol followed by parsing and validation.

## 6. QueryPlan

The QueryPlan is the execution-authoritative representation.

It should contain typed, resolved information such as:

- source/relation
- projection
- filters
- aggregations
- group by
- having
- order by
- limit
- joins
- parameters
- resolved schema references
- provenance
- validation state
- plan version

Nothing reaches SQL compilation unless the plan is valid.

## 7. Typed SQL Compilation

Use separate clause compilers where appropriate:

- ProjectionCompiler
- FilterCompiler
- AggregationCompiler
- GroupByCompiler
- HavingCompiler
- OrderByCompiler
- LimitCompiler
- JoinCompiler

Compilers must not perform NLP, embedding retrieval, conversation resolution, or guessing.

## 8. DuckDB Safety

Use parameterized/prepared statements for untrusted values. DuckDB documents prepared statements and parameter binding for this purpose. Do not concatenate user values into SQL. citeturn0search1turn0search8

Parameterization is not the complete security boundary. Also control:

- allowed relations
- allowed functions/table functions
- external file access
- memory
- threads
- temporary storage
- query/result limits

DuckDB documents resource controls and distinguishes controlled parameterized queries from untrusted SQL input. citeturn0search3

## 9. Conversation State

Only commit new authoritative state after:

```text
Semantic validation
 -> QueryPlan validation
 -> SQL validation
 -> successful execution
 -> result validation
 -> state commit
```

A failed/ambiguous request must preserve the previous successful state.

## 10. Persistence

Persist separately as needed:

- ConversationState
- QueryPlan metadata
- Result metadata/snapshots
- dataset/session metadata

Use versioning and deterministic recovery rules.

## 11. Security Rules

Never allow:

```text
User -> raw SQL -> DuckDB
Qwen -> raw SQL -> DuckDB
```

Required:

```text
User/Qwen
 -> semantic representation
 -> validation
 -> QueryPlan
 -> typed compiler
 -> SQL/resource validation
 -> DuckDB
```

Treat uploaded cell contents as **data, not instructions**.

## 12. Optional Cross-Encoder

Do not add it merely because it appears in the architecture.

Use it only if benchmarking shows a meaningful accuracy improvement:

```text
MiniLM -> Top-K candidates -> Cross-Encoder -> Re-ranked candidates
```

Otherwise it adds model size, CPU cost, memory use, and maintenance without proven benefit.

## 13. Runtime Readiness

Startup should make model/runtime state observable:

```text
[Embedding] Loading all-MiniLM-L6-v2
[Embedding] ONNX session created
[Embedding] Dimension=384
[Embedding] Tokenizer ready
[Embedding] READY

[LLM] Qwen loaded locally
[LLM] Constrained decoding = AVAILABLE/UNAVAILABLE

[DuckDB] READY
[Persistence] READY
[DataDesk] READY
```

Do not silently replace missing models with fake vectors, cloud calls, or undocumented fallbacks.

## 14. Production Verification

Before calling the system production-ready, verify:

### Models

- MiniLM loads at runtime
- tokenizer loads
- output is 384-dimensional
- actual ONNX inference occurs
- Qwen loads locally
- fallback routing works
- constrained decoding is verified if claimed

### Semantic cases

- simple projection
- aggregation
- filters
- grouping
- sorting
- limits
- composite queries
- ambiguous queries
- follow-ups
- invalid fields
- incompatible datatypes
- conflicting evidence

### Security

- SQL injection attempts
- malicious values
- malicious identifiers
- external file/table-function access
- prompt injection in cell contents
- resource exhaustion
- oversized results

### Persistence

- restart recovery
- failed execution
- failed validation
- failed persistence
- state preservation
- plan-version compatibility

### Product

- upload lifecycle
- chat disabled before dataset READY
- SSE/cancellation/recovery
- result rendering
- exports
- conversation history

## 15. Trust Model

Trust should increase as information becomes more constrained and validated:

```text
User language
   ↓
Qwen output / model similarity
   ↓
Exemplar + structural evidence
   ↓
Schema grounding
   ↓
SemanticResolver
   ↓
Validated QueryPlan
   ↓
Validators
   ↓
Typed SQL compiler
   ↓
Controlled DuckDB
```

The closer a component is to execution, the less freedom it should have.

## 16. Final Architectural Rule

> **No component may bypass the Semantic Resolution → QueryPlan → Validation → Typed SQL → DuckDB boundary.**

That boundary is the main reason this architecture is safer and more maintainable than an LLM-to-SQL design.
