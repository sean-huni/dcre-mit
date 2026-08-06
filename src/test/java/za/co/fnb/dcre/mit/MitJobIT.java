package za.co.fnb.dcre.mit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MIT job over the real chain + CockroachDB (Testcontainers, fleet pattern). MIT
 * initializes the mandate spine for every eligible instruction row: creditor
 * account create-if-absent (typed), an immutable man_init_verdict (EXISTS vs
 * CREATED derived from whether the mint inserted), and the guarded spine_state
 * transition to INITIALIZED. Covered end to end: EXISTS vs CREATED paths for
 * SCORE_PASSED CREATE and VALIDATED AMEND/CANCEL rows, the never-process guard
 * (REJECTED / SCORE_DECLINED / SCORE_PENDING / RECEIVED / VALIDATED-CREATE /
 * SUBMITTED never touched), and the resume zero-duplicate audit (a re-run replays
 * the same verdicts with no CREATED->EXISTS flip and no duplicate accounts).
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false"})
class MitJobIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static final Path EXCHANGE = freshExchangeRoot();

    static {
        CRDB.start();
    }

    static Path freshExchangeRoot() {
        try {
            return Files.createTempDirectory("mit-seam-it");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE::toString);
    }

    @Autowired
    Job misJob;
    @Autowired
    JobOperator jobOperator;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seedReference() {
        ManTestTables.createSpine(jdbc);
    }

    private JobExecution run(final UUID arrival, final String attempt) throws Exception {
        final JobParametersBuilder b = new JobParametersBuilder()
                .addString("arrival.id", arrival.toString(), true);
        if (attempt != null) {
            b.addString("attempt", attempt, true);
        }
        final JobParameters params = b.toJobParameters();
        return jobOperator.start(misJob, params);
    }

    private String verdict(final UUID arrival, final int seq) {
        return jdbc.queryForObject(
                "SELECT action FROM man_init_verdict WHERE arrival_id=? AND sequence=?",
                String.class, arrival, seq);
    }

    private List<String> spineStates(final UUID arrival) {
        return jdbc.queryForList(
                "SELECT spine_state FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, arrival);
    }

    private boolean accountExists(final String number) {
        return jdbc.queryForObject("SELECT count(*) > 0 FROM account WHERE account_number=?",
                Boolean.class, number);
    }

    private int accountCount() {
        return jdbc.queryForObject("SELECT count(*) FROM account", Integer.class);
    }

    @Test
    void scorePassedCreateInitsCreditorExistsAndCreatedThenInitialized() throws Exception {
        ManTestTables.seedAccount(jdbc, "6200000010", "CHQ"); // pre-existing creditor
        final int accountsBefore = accountCount();
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-A", "6200000010", "SCORE_PASSED");
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-B", "6200000020", "SCORE_PASSED");

        final JobExecution run = run(arrival, null);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());
        // Plain single-step job: the technical exit code is COMPLETED; the BUSINESS_ACCEPTED
        // business verdict is carried by the outcome seam file asserted below (AIS pattern).
        assertEquals("COMPLETED", run.getExitStatus().getExitCode());

        assertEquals("EXISTS", verdict(arrival, 1), "pre-existing creditor account -> EXISTS");
        assertEquals("CREATED", verdict(arrival, 2), "absent creditor account -> CREATED");
        assertTrue(accountExists("6200000020"), "the absent creditor was minted (R-11)");
        assertEquals(accountsBefore + 1, accountCount(), "exactly the one unknown creditor minted");
        // "typed": the minted creditor account carries the synthetic CHQ type + ACTIVE status.
        assertEquals("CHQ", jdbc.queryForObject(
                "SELECT account_type_code FROM account WHERE account_number=?", String.class, "6200000020"));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM account WHERE account_number=?", String.class, "6200000020"));
        assertEquals(List.of("INITIALIZED", "INITIALIZED"), spineStates(arrival));

        Assumptions.assumeTrue(System.getenv("JOB_NAME") == null, "seam name requires JOB_NAME absent");
        final Path seam = EXCHANGE.resolve("outcomes").resolve("local-mit-" + run.getId());
        assertTrue(Files.exists(seam), "expected self-describing seam file at " + seam);
        assertEquals(List.of("BUSINESS_ACCEPTED"), Files.readAllLines(seam));
    }

    @Test
    void validatedAmendAndCancelAdvanceFromValidatedToInitialized() throws Exception {
        ManTestTables.seedAccount(jdbc, "6200000030", "CHQ"); // known amend creditor
        final int accountsBefore = accountCount();
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertEntry(jdbc, arrival, 1, "AMEND", "MREF-C", "6200000030", "VALIDATED");
        ManTestTables.insertEntry(jdbc, arrival, 2, "CANCEL", "MREF-D", "6200000040", "VALIDATED");

        run(arrival, null);

        assertEquals("EXISTS", verdict(arrival, 1), "AMEND known creditor -> EXISTS");
        assertEquals("CREATED", verdict(arrival, 2), "CANCEL unknown creditor -> create-if-absent CREATED");
        assertTrue(accountExists("6200000040"));
        assertEquals(accountsBefore + 1, accountCount());
        assertEquals(List.of("INITIALIZED", "INITIALIZED"), spineStates(arrival),
                "AMEND/CANCEL advance to INITIALIZED from VALIDATED (they skip MAS)");
    }

    @Test
    void neverInitializesRejectedScoreDeclinedPendingReceivedOrValidatedCreate() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-E", "6200000051", "REJECTED");
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-F", "6200000052", "SCORE_DECLINED");
        ManTestTables.insertEntry(jdbc, arrival, 3, "CREATE", "MREF-G", "6200000053", "SCORE_PENDING");
        ManTestTables.insertEntry(jdbc, arrival, 4, "CREATE", "MREF-H", "6200000054", "RECEIVED");
        ManTestTables.insertEntry(jdbc, arrival, 5, "CREATE", "MREF-I", "6200000055", "VALIDATED"); // CREATE awaiting MAS
        final int accountsBefore = accountCount();

        final JobExecution run = run(arrival, null);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM man_init_verdict WHERE arrival_id=?", Integer.class, arrival),
                "MIT never writes a verdict for an ineligible row");
        for (int seq = 1; seq <= 5; seq++) {
            assertNull(jdbc.queryForObject("SELECT max(action) FROM man_init_verdict"
                    + " WHERE arrival_id=? AND sequence=?", String.class, arrival, seq));
        }
        assertEquals(List.of("REJECTED", "SCORE_DECLINED", "SCORE_PENDING", "RECEIVED", "VALIDATED"),
                spineStates(arrival), "no ineligible row is transitioned to INITIALIZED");
        assertEquals(accountsBefore, accountCount(), "no creditor of an ineligible row is minted");
        for (final String acc : List.of("6200000051", "6200000052", "6200000053", "6200000054", "6200000055")) {
            assertFalse(accountExists(acc), "ineligible creditor " + acc + " must not be minted");
        }
    }

    @Test
    void reRunIsIdempotentZeroDuplicateAndVerdictsImmutable() throws Exception {
        ManTestTables.seedAccount(jdbc, "6200000060", "CHQ"); // pre-existing -> EXISTS
        final int accountsBefore = accountCount();
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertEntry(jdbc, arrival, 1, "CREATE", "MREF-J", "6200000060", "SCORE_PASSED");
        ManTestTables.insertEntry(jdbc, arrival, 2, "CREATE", "MREF-K", "6200000070", "SCORE_PASSED");

        assertEquals(BatchStatus.COMPLETED, run(arrival, "1").getStatus());
        assertEquals("EXISTS", verdict(arrival, 1));
        assertEquals("CREATED", verdict(arrival, 2));
        assertEquals(List.of("INITIALIZED", "INITIALIZED"), spineStates(arrival));
        final int accountsAfterFirst = accountCount();
        assertEquals(accountsBefore + 1, accountsAfterFirst);

        // Fresh JobInstance re-processing the same arrival: the verdict scope re-reads the
        // now-INITIALIZED rows, so createIfAbsent + the verdict ON CONFLICT DO NOTHING are
        // genuinely re-exercised. The creditor of seq 2 now EXISTS, but the verdict stays
        // CREATED (immutable, never flips), and no duplicate account or verdict is written.
        assertEquals(BatchStatus.COMPLETED, run(arrival, "2").getStatus());

        assertEquals("EXISTS", verdict(arrival, 1), "verdict immutable on resume");
        assertEquals("CREATED", verdict(arrival, 2), "verdicts immutable: no CREATED->EXISTS flip on re-run");
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM man_init_verdict WHERE arrival_id=?", Integer.class, arrival));
        assertEquals(jdbc.queryForObject(
                        "SELECT count(*) FROM man_init_verdict WHERE arrival_id=?", Integer.class, arrival),
                jdbc.queryForObject(
                        "SELECT count(DISTINCT (arrival_id, sequence)) FROM man_init_verdict WHERE arrival_id=?",
                        Integer.class, arrival),
                "zero-duplicate audit: count == distinct business identity");
        assertEquals(accountsAfterFirst, accountCount(), "re-run mints no duplicate accounts (R-05)");
        assertEquals(List.of("INITIALIZED", "INITIALIZED"), spineStates(arrival),
                "re-run does not re-transition (guard excludes already-INITIALIZED rows)");
    }
}
