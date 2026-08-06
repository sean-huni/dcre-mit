package za.co.fnb.dcre.mit.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Read/transition anchor over the MRR-owned {@code mandate_request_entry}
 * (shared dcre_man). MIT never inserts spine rows; it reads the eligible ones
 * ({@link za.co.fnb.dcre.mit.data.repo.ManRequestEntryRepo}) and advances only
 * the {@code spine_state} column it owns to INITIALIZED via the guarded
 * transition ({@link za.co.fnb.dcre.mit.data.repo.ManInitTransitionRepo}),
 * ruling note 2.
 */
@Table("mandate_request_entry")
public class ManRequestEntryView {

    @Id
    private UUID id;

    public UUID getId() {
        return id;
    }
}
