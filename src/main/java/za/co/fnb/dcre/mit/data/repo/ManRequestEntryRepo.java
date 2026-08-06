package za.co.fnb.dcre.mit.data.repo;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mit.data.model.ManInitRef;
import za.co.fnb.dcre.mit.data.model.ManRequestEntryView;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the MRR-owned mandate_request_entry spine (MIT never inserts rows).
 * The verdict scope is the set of rows MIT is responsible for initialising:
 * <ul>
 *   <li>{@code SCORE_PASSED} - CREATE rows that cleared the MAF score gate,</li>
 *   <li>{@code VALIDATED} AMEND/CANCEL rows - these skip MAF and stay VALIDATED,</li>
 *   <li>{@code INITIALIZED} - rows MIT already processed, re-read so a resume
 *       re-exercises the idempotent verdict/account writes on the SAME identity.</li>
 * </ul>
 * REJECTED / SCORE_DECLINED / SCORE_PENDING / RECEIVED / VALIDATED-CREATE / SUBMITTED
 * are all excluded, so MIT NEVER initialises a rejected or score-declined row and
 * never races MAF for a still-unscored CREATE. All spine_state WRITES go through
 * {@link ManInitTransitionRepo} (single-column single-writer, R-04).
 */
public interface ManRequestEntryRepo extends Repository<ManRequestEntryView, UUID> {

    @Query(value = """
            SELECT sequence, creditor_account
            FROM mandate_request_entry
            WHERE arrival_id = :arrivalId
              AND (spine_state = 'SCORE_PASSED'
                   OR spine_state = 'INITIALIZED'
                   OR (spine_state = 'VALIDATED' AND action_code IN ('AMEND', 'CANCEL')))
            ORDER BY sequence""", rowMapperClass = ManInitRefMapper.class)
    List<ManInitRef> findVerdictScope(@Param("arrivalId") UUID arrivalId);
}
