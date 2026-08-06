package za.co.fnb.dcre.mit.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mit.service.MitInitTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

/**
 * MIT job shape (single-step, AIS skeleton clone): initStep initializes the
 * mandate spine for every eligible instruction row of the arrival: creditor
 * account create-if-absent (typed), an immutable man_init_verdict
 * (EXISTS|CREATED) keyed on the full identity (arrival_id, sequence), and the
 * guarded spine_state transition to INITIALIZED (ruling note 2: MIT owns the
 * INITIALIZED state column, MSR owns the projection FSM). Identifying
 * JobParameter: arrival.id (R-16). Runs on the default SERIALIZABLE isolation
 * (only PRG carries READ COMMITTED, SCRUM-90).
 *
 * <p>MIT is DB-only for BUSINESS file I/O (R-30), but the outcome seam is AGT
 * orchestration plumbing: without it the OutcomeWatcher/Reconciler can never
 * observe a business verdict and reaps the stage as TECH_FAILED. MIT has no
 * business rejection of its own (REJECTED/SCORE_DECLINED rows are never in
 * scope), so the seam verdict is the constant BUSINESS_ACCEPTED, as with AIS.
 */
@Configuration
public class MitJobConfig {

    @Bean
    public Step initStep(final JobRepository repo, final PlatformTransactionManager tx,
                         final MitInitTasklet tasklet) {
        // The verdict step WRITES man_init_verdict + account rows concurrently with the
        // fleet's heavy writers; CRDB 40001 commit-time aborts are normal under contention
        // and are retried in a fresh tx by the shared handler (retry, never skip). Every
        // write is idempotent (INSERT ... ON CONFLICT DO NOTHING + guarded UPDATE), so a
        // step-level re-run no-ops over already-committed work.
        return new StepBuilder("initStep", repo)
                .tasklet(tasklet, tx)
                .exceptionHandler(new CrdbRetryExceptionHandler("MIT"))
                .build();
    }

    @Bean
    public Job misJob(final JobRepository repo, final Step initStep,
                      final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        return new JobBuilder("misJob", repo)
                .listener(new OutcomeSeamListener("mit", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(initStep)
                .build();
    }
}
