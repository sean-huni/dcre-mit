package za.co.fnb.dcre.mit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mit.service.MitInitService.InitCounts;

import java.util.UUID;

/** Thin entry adapter (3-tier, configuration.md point 21). */
@Component
public class MitInitTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(MitInitTasklet.class);

    private final MitInitService service;

    public MitInitTasklet(final MitInitService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        final InitCounts counts = service.init(arrivalId);
        log.info("Mandate init for arrival {}: {} existing, {} created", arrivalId,
                counts.existing(), counts.created());
        final var context = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        context.putInt("existing", counts.existing());
        context.putInt("created", counts.created());
        return RepeatStatus.FINISHED;
    }
}
