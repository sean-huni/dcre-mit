package za.co.fnb.dcre.mit.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/**
 * Per-instruction mandate-init verdict (AIS ais_verdict pattern); action is
 * EXISTS or CREATED. Written only through
 * {@link za.co.fnb.dcre.mit.data.repo.ManInitVerdictRepo#insertIfAbsent}
 * (guarded INSERT ... ON CONFLICT DO NOTHING), immutable once written so a
 * rerun never flips CREATED to EXISTS.
 */
@Table("man_init_verdict")
public class ManInitVerdictEntity extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String action;

    public UUID getArrivalId() {
        return arrivalId;
    }

    public Integer getSequence() {
        return sequence;
    }

    public String getAction() {
        return action;
    }
}
