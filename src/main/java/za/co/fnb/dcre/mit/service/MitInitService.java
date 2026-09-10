package za.co.fnb.dcre.mit.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mit.data.model.ManInitRef;
import za.co.fnb.dcre.mit.data.repo.AccountRepo;
import za.co.fnb.dcre.mit.data.repo.ManInitTransitionRepo;
import za.co.fnb.dcre.mit.data.repo.ManInitVerdictRepo;
import za.co.fnb.dcre.mit.data.repo.ManRequestEntryRepo;

import java.util.List;
import java.util.UUID;

/**
 * Business tier: post-MAS mandate initialization for the M10 mandates flow
 * (MRR -> MRV -> MAS -> MIT -> { MIR || MRW }, SCRUM-77; DB-only, R-30). For
 * every eligible spine row of the arrival (SCORE_PASSED CREATE, or VALIDATED
 * AMEND/CANCEL): the creditor account is minted create-if-absent (typed), an
 * immutable man_init_verdict is written (EXISTS when the account already
 * existed, CREATED when it was minted, mirroring the PAI immutable-verdict
 * pattern), and the spine advances to INITIALIZED. No projection row is written:
 * MSR is the sole writer of the mandate FSM (ruling note 2).
 *
 * <p>Idempotent + resumable: every write is create-if-absent on the business
 * identity (account_number, or (arrival_id, sequence) for the verdict), and the
 * spine transition is a GUARDED atomic UPDATE over exactly the eligible prior
 * states, so a re-run (step-level retry or a fresh JobInstance) no-ops over
 * committed work and never flips CREATED to EXISTS. Whole-arrival single pass:
 * mandate instruction books are low volume (KISS), and the step runs under the
 * shared CRDB 40001 retry (a contended abort re-runs the tasklet in a fresh tx).
 */
@Service
public class MitInitService {

    /** Per-run tallies; existing + created = eligible instruction rows initialised. */
    public record InitCounts(int existing, int created) {
    }

    private final ManRequestEntryRepo entries;
    private final AccountRepo accounts;
    private final ManInitVerdictRepo verdicts;
    private final ManInitTransitionRepo spine;

    public MitInitService(final ManRequestEntryRepo entries, final AccountRepo accounts,
                          final ManInitVerdictRepo verdicts, final ManInitTransitionRepo spine) {
        this.entries = entries;
        this.accounts = accounts;
        this.verdicts = verdicts;
        this.spine = spine;
    }

    public InitCounts init(final UUID arrivalId) {
        final List<ManInitRef> scope = entries.findVerdictScope(arrivalId);
        int existing = 0;
        int created = 0;
        for (final ManInitRef ref : scope) {
            final String action = initAccount(ref.creditorAccount());
            verdicts.insertIfAbsent(arrivalId, ref.sequence(), action);
            if ("CREATED".equals(action)) {
                created++;
            } else {
                existing++;
            }
        }
        // Single writer of INITIALIZED: guarded over the eligible prior states only,
        // so already-INITIALIZED rows re-read for the idempotent verdict/account writes
        // above are not re-transitioned, and REJECTED/SCORE_DECLINED are never touched.
        spine.initializeEligible(arrivalId);
        return new InitCounts(existing, created);
    }

    /**
     * Mints the creditor account if absent and derives the verdict from whether the
     * insert actually happened (atomic with the write, never a racy check-then-act):
     * one affected row means the account was absent and is now minted (CREATED),
     * zero means it already existed (EXISTS). A row with no creditor account carries
     * nothing to mint, so it is a trivial EXISTS.
     */
    private String initAccount(final String creditorAccount) {
        if (creditorAccount == null || creditorAccount.isBlank()) {
            return "EXISTS";
        }
        return accounts.createIfAbsent(creditorAccount) == 1 ? "CREATED" : "EXISTS";
    }
}
