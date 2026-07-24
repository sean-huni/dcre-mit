package za.co.fnb.dcre.mis.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mis.data.repo.AccountRepo;
import za.co.fnb.dcre.mis.data.repo.ManInitTransitionRepo;
import za.co.fnb.dcre.mis.data.repo.ManInitVerdictRepo;
import za.co.fnb.dcre.mis.data.repo.ManRequestEntryRepo;

import java.util.UUID;

/**
 * RED STUB (to be implemented): no-op initialize. See the GREEN implementation
 * commit for the real mandate-init logic.
 */
@Service
public class MisInitService {

    /** Per-run tallies; existing + created = eligible instruction rows initialised. */
    public record InitCounts(int existing, int created) {
    }

    private final ManRequestEntryRepo entries;
    private final AccountRepo accounts;
    private final ManInitVerdictRepo verdicts;
    private final ManInitTransitionRepo spine;

    public MisInitService(final ManRequestEntryRepo entries, final AccountRepo accounts,
                          final ManInitVerdictRepo verdicts, final ManInitTransitionRepo spine) {
        this.entries = entries;
        this.accounts = accounts;
        this.verdicts = verdicts;
        this.spine = spine;
    }

    public InitCounts init(final UUID arrivalId) {
        return new InitCounts(0, 0);
    }
}
