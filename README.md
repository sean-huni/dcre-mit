# dcre-mit

Mandates Init Service: the fifth stage of the M10 mandates flow (SCRUM-77) that initializes the mandate spine written by MRR and advanced by MRV/MAS. For every eligible instruction row it mints the creditor account in the shared `dcre_man` account master if absent, records a durable per-record init verdict in `man_init_verdict`, and transitions the row's `spine_state` to `INITIALIZED` in `dcre_man`.

## What it does

MIT is the DAG successor of MAS (`MRR -> MRV -> MAS -> MIT -> { MIR || MRW }`). AGT launches it as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16). It owns the `spine_state -> INITIALIZED` transition (ruling note 2: MRR writes spine ROWS, MRV/MAS/MIT each advance the state columns they own; MSR is the sole writer of the mandate PROJECTION FSM, so MIT writes no projection row).

### Eligibility (never process a rejected or unscored row)

MIT initializes exactly:

- `SCORE_PASSED` rows: CREATE instructions that cleared the MAS affordability gate;
- `VALIDATED` AMEND/CANCEL rows: these skip MAS (CREATE-only score gate) and stay VALIDATED.

`REJECTED`, `SCORE_DECLINED`, `SCORE_PENDING`, `RECEIVED`, a still-unscored `VALIDATED` CREATE row, and a downstream `SUBMITTED` row are all outside scope: MIT never initializes a rejected or score-declined mandate, and never races MAS for an unscored CREATE.

### Account create-if-absent + immutable verdict (PAI pattern)

For each eligible row MIT mints the creditor account into the shared `dcre_man` `account` master with an idempotent `INSERT ... ON CONFLICT (account_number) DO NOTHING` (typed: synthetic `CHQ` / `ACTIVE`, per the account-master shape; canonical type roster + consolidation ride M11, ruling note 1 / A-62). The affected-row count is read directly to derive the verdict, so the decision is atomic with the write, never a racy check-then-act:

- the insert inserted a row (count 1) -> the account was absent -> verdict `CREATED`;
- the insert hit the conflict (count 0) -> the account already existed -> verdict `EXISTS`.

The verdict lands in `man_init_verdict` through `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING`, keyed on the FULL business identity, so it is immutable: a rerun never flips `CREATED` to `EXISTS`.

### Spine transition (guarded, single writer)

`data/repo/ManInitTransitionRepo` advances the eligible rows to `INITIALIZED` with a GUARDED atomic `UPDATE ... WHERE spine_state = 'SCORE_PASSED' OR (spine_state = 'VALIDATED' AND action_code IN ('AMEND','CANCEL'))`, so MIT is the single writer of `INITIALIZED`, the transition is idempotent + resumable (a re-run touches zero already-INITIALIZED rows), and it can never clobber a downstream `SUBMITTED` or an out-of-scope `REJECTED`/`SCORE_DECLINED`.

### Idempotency and resume (chaos-monkey gate)

- `man_init_verdict` and the creditor-account mint are both `INSERT ... ON CONFLICT DO NOTHING` on their business identities (never UPSERT on the PK: CRDB resolves UPSERT on the PK only), so a re-run is a zero-duplicate no-op.
- The verdict scope deliberately re-reads already-`INITIALIZED` rows, so a resume re-exercises the idempotent writes on the same identity and proves the `CREATED->EXISTS` non-flip; the narrower transition guard makes the state advance a no-op on re-run.
- Verified by `MitJobIT.reRunIsIdempotentZeroDuplicateAndVerdictsImmutable`: a fresh JobInstance reprocessing the same arrival leaves `count(*) == count(DISTINCT (arrival_id, sequence))`, the same verdicts, no duplicate accounts, and the spine unchanged.

## Architecture

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the PAI/MRV skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, platform-batch persistent JobRepository (`@Import BatchJdbcConfig`, `MIT_BATCH_` prefix), layer-first packages (`config/`, `service/`, `data/model/`, `data/repo/`), constructor injection, `.yml`-only config on the default SERIALIZABLE isolation (only CRG carries READ COMMITTED, SCRUM-90).

1. `initStep` (tasklet): for every eligible spine row, the creditor account create-if-absent + the immutable `man_init_verdict` + the guarded `spine_state` transition to `INITIALIZED`. Carries the shared `CrdbRetryExceptionHandler` (40001 re-runs the tasklet in a fresh tx).

`BatchMetaConfig` sweeps stale `MIT_BATCH_` executions to ABANDONED before the runner fires (A-39a). MIT is DB-only for business I/O (R-30); the constant `BUSINESS_ACCEPTED` outcome seam is AGT orchestration plumbing (MIT has no business rejection of its own).

## Database

Liquibase owns the schema in the shared `dcre_man`, per-service history tables (`mit_databasechangelog` / `mit_databasechangeloglock`), calendar layout `2026/07/`, pure-XML typed changesets. The changelog is the SCRUM-107 **v1 baseline**: every DCRE database is dropped and recreated for the direct cut-over, so there is no historic state to converge and no retrofit apparatus (`validCheckSum`, defensive `IF NOT EXISTS`, create-then-drop pairs). The only `MARK_RAN` preconditions that remain are convergence guards on the shared reference core, and each names the writer it converges with:

- `000-man-core-bootstrap.xml`: byte-equivalent VERBATIM copy of the shared-core bootstrap (only the changeset ids are `mit-` prefixed, per the shared-core canon) so concurrent first runs of any M-service converge. `account_type` / `account` / `mandate_reason_code` keep their `MARK_RAN` guards because the infra bootstrap (`dcre-infra scripts/seed-man-core.sql`) and the other nine M-services can each create them first.
- `001-man-init-verdict.xml`: `man_init_verdict` (`arrival_id`, `sequence`, `action`, UNIQUE (`arrival_id`, `sequence`)). MIT is the sole writer (R-04) and the sole CREATOR, so this changeset carries NO `MARK_RAN` guard: on a v1 database the table cannot already exist.
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6.0.4 metadata as pure typed XML, one changeset per object, prefixed `MIT_BATCH_`, EXIT_MESSAGE widened to TEXT for CockroachDB. No guards: MIT is the only creator of its own `MIT_BATCH_` objects.

MIT reads the MRR-owned spine (`mandate_request_entry`) and mints into the shared core (`account`); it never re-declares the spine in its own changelog (MRV reads the mrr-owned spine the same way).

## Tests

- `MitJobIT`: the full job over real CockroachDB (Testcontainers) for the EXISTS/CREATED paths (SCORE_PASSED CREATE + VALIDATED AMEND/CANCEL), the never-process guard (REJECTED / SCORE_DECLINED / SCORE_PENDING / RECEIVED / VALIDATED-CREATE untouched), and the resume/idempotency zero-duplicate audit.
