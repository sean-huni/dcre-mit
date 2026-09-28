# dcre-mit

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories) table.

Mandates Init Service: the fifth stage of the M10 mandates flow (SCRUM-77) that initializes the mandate spine written by MRR and advanced by MRV/MAS. For every eligible instruction row it mints the creditor account in the shared `dcre_man` account master if absent, records a durable per-record init verdict in `man_init_verdict`, and transitions the row's `spine_state` to `INITIALIZED` in `dcre_man`.

## What it does

| | |
|---|---|
| Stage code | `MIT` (AGT `Stage.MIT`); renamed from MIS, and the Batch job bean is still `misJob` |
| Family | Mandates (pain.009, `dcre_man`) |
| Leg | REQ |
| Trigger | Arrival-launched: AGT launches it when MAS completes on an `onhost-req-man` arrival |
| Upstream | `MAS` |
| Downstream | Fork: `MIR` and `MRW`, both terminal |
| Diagram sheet | `dcre-mandates-req` in the design register |

DAG position read from AGT `origin/dev` `RouteDags.java` and `Stage.java` (checked 2026-09-28): `MRR -> MRV -> MAS -> MIT -> fork {MIR, MRW}`.

MIT is the DAG successor of MAS (`MRR -> MRV -> MAS -> MIT -> { MIR || MRW }`). AGT launches it as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16). It owns the `spine_state -> INITIALIZED` transition (ruling note 2: MRR writes spine ROWS, MRV/MAS/MIT each advance the state columns they own). MIT writes no mandate-status row of any kind.

### Eligibility (never process a rejected or unscored row)

MIT initializes exactly:

- `SCORE_PASSED` rows: CREATE instructions that cleared the MAS affordability gate;
- `VALIDATED` AMEND/CANCEL rows: these skip MAS (CREATE-only score gate) and stay VALIDATED.

`REJECTED`, `SCORE_DECLINED`, `SCORE_PENDING`, `RECEIVED`, a still-unscored `VALIDATED` CREATE row, and a downstream `SUBMITTED` row are all outside scope: MIT never initializes a rejected or score-declined mandate, and never races MAS for an unscored CREATE.

### Account create-if-absent + immutable verdict (PAI pattern)

For each eligible row MIT mints the creditor account into the shared `dcre_man` `account` master with an idempotent `INSERT ... ON CONFLICT (account_number) DO NOTHING` (typed: synthetic `CHQ` / `ACTIVE`, per the account-master shape; canonical type roster + consolidation ride M11, ruling note 1 / A-62). The affected-row count is read directly to derive the verdict, so the decision is atomic with the write, never a racy check-then-act (a row with no `creditor_account` has nothing to mint and is a trivial `EXISTS`):

- the insert inserted a row (count 1) -> the account was absent -> verdict `CREATED`;
- the insert hit the conflict (count 0) -> the account already existed -> verdict `EXISTS`.

The verdict lands in `man_init_verdict` through `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING`, keyed on the FULL business identity, so it is immutable: a rerun never flips `CREATED` to `EXISTS`.

### Spine transition (guarded, single writer)

`data/repo/ManInitTransitionRepo` advances the eligible rows to `INITIALIZED` with a GUARDED atomic `UPDATE ... WHERE spine_state = 'SCORE_PASSED' OR (spine_state = 'VALIDATED' AND action_code IN ('AMEND','CANCEL'))`, so MIT is the single writer of `INITIALIZED`, the transition is idempotent + resumable (a re-run touches zero already-INITIALIZED rows), and it can never clobber a downstream `SUBMITTED` or an out-of-scope `REJECTED`/`SCORE_DECLINED`.

### Idempotency and resume (chaos-monkey gate)

- `man_init_verdict` and the creditor-account mint are both `INSERT ... ON CONFLICT DO NOTHING` on their business identities (never UPSERT on the PK: CRDB resolves UPSERT on the PK only), so a re-run is a zero-duplicate no-op.
- The verdict scope deliberately re-reads already-`INITIALIZED` rows, so a resume re-exercises the idempotent writes on the same identity and proves the `CREATED->EXISTS` non-flip; the narrower transition guard makes the state advance a no-op on re-run.
- Verified by `MitJobIT.reRunIsIdempotentZeroDuplicateAndVerdictsImmutable`: a fresh JobInstance reprocessing the same arrival leaves `count(*) == count(DISTINCT (arrival_id, sequence))`, the same verdicts, no duplicate accounts, and the spine unchanged.

## Architecture and principles

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the PAI/MRV skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, platform-batch persistent JobRepository (`@Import BatchJdbcConfig`, `MIT_BATCH_` prefix), layer-first packages (`config/`, `service/`, `data/model/`, `data/repo/`), constructor injection, `.yml`-only config on the default SERIALIZABLE isolation (only CRG carries READ COMMITTED, SCRUM-90).

1. `initStep` (tasklet): for every eligible spine row, the creditor account create-if-absent + the immutable `man_init_verdict` + the guarded `spine_state` transition to `INITIALIZED`. Carries the shared `CrdbRetryExceptionHandler` (40001 re-runs the tasklet in a fresh tx).

`BatchMetaConfig` sweeps stale `MIT_BATCH_` executions to ABANDONED before the runner fires (A-39a). MIT is DB-only for business I/O (R-30); the constant `BUSINESS_ACCEPTED` outcome seam is AGT orchestration plumbing (MIT has no business rejection of its own).

### Database

One business datasource: `dcre_man` via `DCRE_DB_URL` / `DCRE_DB_USER` / `DCRE_DB_PASSWORD`. A second datasource (`DCRE_AGTOPS_DB_*`, platform-batch `HeartbeatDatasourceConfig`) carries only the `HeartbeatWriter` liveness stamp into `agt_ops`. `OneMandateDatabaseTest` guards that the account master MIT mints into is `dcre_man.account`, the same table MRV validates against.

- Writes: `account` (`INSERT ... ON CONFLICT (account_number) DO NOTHING`, type `CHQ`, status `ACTIVE`), `man_init_verdict`, `mandate_request_entry.spine_state` (`SCORE_PASSED`, or `VALIDATED` AMEND/CANCEL, `-> INITIALIZED`), and its own `MIT_BATCH_*` metadata.
- Reads: `mandate_request_entry` (verdict scope: `SCORE_PASSED`, `INITIALIZED`, and `VALIDATED` AMEND/CANCEL rows of the arrival).
- Outcome seam: `OutcomeSeamListener("mit", ...)` writes the constant `BUSINESS_ACCEPTED` to `<DCRE_EXCHANGE_ROOT>/outcomes/<JOB_NAME>`.

Liquibase owns the schema in the shared `dcre_man`, per-service history tables (`mit_databasechangelog` / `mit_databasechangeloglock`), calendar layout `2026/07/`, pure-XML typed changesets. The changelog is the SCRUM-107 **v1 baseline**: every DCRE database is dropped and recreated for the direct cut-over, so there is no historic state to converge and no retrofit apparatus (`validCheckSum`, defensive `IF NOT EXISTS`, create-then-drop pairs). The only `MARK_RAN` preconditions that remain are convergence guards on the shared reference core, and each names the writer it converges with:

- `000-man-core-bootstrap.xml`: the shared-core bootstrap, structurally identical (comments and the `mit-` changeset id prefix aside) to the copies in mrr, mrv, mas and mir (checked 2026-09-28), so concurrent first runs of any mandates service converge. `account_type` / `account` / `mandate_reason_code` keep their `MARK_RAN` guards because the infra bootstrap (`dcre-infra scripts/seed-man-core.sql`) and the sibling mandates services can each create them first.
- `001-man-init-verdict.xml`: `man_init_verdict` (`arrival_id`, `sequence`, `action`, UNIQUE (`arrival_id`, `sequence`)). MIT is the sole writer (R-04) and the sole CREATOR, so this changeset carries NO `MARK_RAN` guard: on a v1 database the table cannot already exist.
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6.0.4 metadata as pure typed XML, one changeset per object, prefixed `MIT_BATCH_`, EXIT_MESSAGE widened to TEXT for CockroachDB. No guards: MIT is the only creator of its own `MIT_BATCH_` objects.

MIT reads the MRR-owned spine (`mandate_request_entry`) and mints into the shared core (`account`); it never re-declares the spine in its own changelog (MRV reads the mrr-owned spine the same way).

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem` (`sdk env`); `build.gradle` sets source/target compatibility 25.
- Gradle 9.5.1 through the committed wrapper (`gradle/wrapper/gradle-wrapper.properties`).
- Docker: Testcontainers CockroachDB for the tests, and the image build.
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0` and `platform-batch:0.1.0`.
- For a real local run: CockroachDB on `localhost:26257` with `dcre_man` (MRR's spine already present) and `agt_ops`.

## Quickstart

Clean clone, no `.env` needed (working dev defaults committed in `application.yml`):

```bash
./gradlew test          # full suite, Docker required
./gradlew bootJar       # build/libs/mit-2.0.jar
java -jar build/libs/mit-2.0.jar 'arrival.id=<uuid>,java.lang.String,true'
```

## Configuration

All keys live in `src/main/resources/application.yml` (the only profile). Spring relaxed binding lets any property be overridden by its environment-variable form, so this table is the documented set, not a closed total.

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Mandates DB (CockroachDB) |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB user |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root, used only for the outcome seam (R-30: no boundary-file I/O) |
| `JOB_NAME` | unset | Set by AGT; names the outcome seam file |

Fixed in yml (no env placeholder): `dcre.batch.table-prefix: MIT_BATCH_`, Liquibase history tables `mit_databasechangelog` / `mit_databasechangeloglock`, virtual threads on.

## Testing

`./gradlew test` (Docker required; `useJUnitPlatform()` with no filter, so `*IT` classes run in the same task). Testcontainers image: `cockroachdb/cockroach:v26.2.3`.

- `MitJobIT`: the full job over real CockroachDB for the EXISTS/CREATED paths (SCORE_PASSED CREATE + VALIDATED AMEND/CANCEL), the never-process guard (REJECTED / SCORE_DECLINED / SCORE_PENDING / RECEIVED / VALIDATED-CREATE untouched), and the resume/idempotency zero-duplicate audit.
- `OneMandateDatabaseTest`: structural and literal scans, each with a positive control, that MIT's account writes stay on the one `dcre_man` datasource.

No Cucumber features ship in this repo, although the Cucumber dependencies are declared.

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mit:<version> .
kind load docker-image --name dcre-dev dcre-mit:<version>
```

Image base: `eclipse-temurin:25-jre-alpine` (`Dockerfile` copies `build/libs/mit-2.0.jar`). AGT launches MIT as a K8s Job in the mandates flow namespace (`AGT_NAMESPACE_MAN`, default `dcre-man`) with the image from `AGT_MIT_IMAGE` (empty default = launch-disabled) and the single arg `arrival.id=<uuid>`. AGT injects `JOB_NAME`, `DCRE_DB_URL` (from `AGT_MAN_SERVICE_DB_URL`, default `dcre_man` on `crdb.dcre.svc.cluster.local`), `DCRE_EXCHANGE_ROOT=/exchange` (the `dcre-exchange` PVC), `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER` (AGT `origin/dev` `JobLauncher.java` and `application.yml`, checked 2026-09-28). The cluster itself, and the fleet-wide image switch, live in dcre-infra.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)
