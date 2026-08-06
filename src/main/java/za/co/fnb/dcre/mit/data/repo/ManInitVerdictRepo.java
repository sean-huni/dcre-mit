package za.co.fnb.dcre.mit.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mit.data.model.ManInitVerdictEntity;

import java.util.UUID;

public interface ManInitVerdictRepo extends CrudRepository<ManInitVerdictEntity, UUID> {

    /**
     * Verdict is immutable once written: DO NOTHING (never DO UPDATE), so a
     * rerun cannot flip CREATED to EXISTS. NEVER UPSERT INTO: CRDB resolves
     * UPSERT on PK only; business identity is (arrival_id, sequence).
     */
    @Modifying
    @Query("""
            INSERT INTO man_init_verdict (id, arrival_id, sequence, action)
            VALUES (gen_random_uuid(), :arrivalId, :sequence, :action)
            ON CONFLICT (arrival_id, sequence) DO NOTHING""")
    void insertIfAbsent(@Param("arrivalId") UUID arrivalId, @Param("sequence") int sequence,
                        @Param("action") String action);

    long countByArrivalId(UUID arrivalId);
}
