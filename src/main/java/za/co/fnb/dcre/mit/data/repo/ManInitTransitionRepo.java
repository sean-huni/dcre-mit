package za.co.fnb.dcre.mit.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mit.data.model.ManRequestEntryView;

import java.util.UUID;

/**
 * Single writer of the {@code spine_state} column MIT owns (ruling note 2): the
 * SCORE_PASSED|VALIDATED(AMEND/CANCEL) -> INITIALIZED transition. It is a GUARDED
 * atomic UPDATE (persistence.md) over exactly the currently-eligible prior states,
 * so it is:
 * <ul>
 *   <li>idempotent + resumable: a re-run touches zero already-INITIALIZED rows,</li>
 *   <li>never-clobbering: REJECTED/SCORE_DECLINED are outside the guard (MIT never
 *       initialises them), and a downstream SUBMITTED is never an eligible prior
 *       state, so MIT cannot overwrite it,</li>
 *   <li>MAF-safe: a still-unscored VALIDATED CREATE row is excluded (only
 *       AMEND/CANCEL advance from VALIDATED).</li>
 * </ul>
 * Native @Query per the guarded-mutation canon (QueryDSL cannot express these).
 */
public interface ManInitTransitionRepo extends Repository<ManRequestEntryView, UUID> {

    @Modifying
    @Query("""
            UPDATE mandate_request_entry SET spine_state = 'INITIALIZED', updated_at = now()
            WHERE arrival_id = :arrivalId
              AND (spine_state = 'SCORE_PASSED'
                   OR (spine_state = 'VALIDATED' AND action_code IN ('AMEND', 'CANCEL')))""")
    int initializeEligible(@Param("arrivalId") UUID arrivalId);
}
