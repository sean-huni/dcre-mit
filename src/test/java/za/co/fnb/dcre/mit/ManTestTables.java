package za.co.fnb.dcre.mit;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * IT seeding helpers. account / account_type / mandate / man_init_verdict come
 * from MIT's own Liquibase (000-man-core-bootstrap.xml + 001-man-init-verdict.xml),
 * so this helper only stands up the MRR-owned spine table
 * (mandate_request_entry, NOT in MIT's changelog, exactly as CtvTestTables stands
 * up the crr-owned tx spine) with the subset of columns the MIT reads, and seeds
 * account + spine rows at a chosen spine_state.
 */
public final class ManTestTables {

    private ManTestTables() {
    }

    public static void createSpine(final JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    arrival_id UUID NOT NULL,
                    sequence INT NOT NULL,
                    record_type VARCHAR(2) NOT NULL DEFAULT 'MD',
                    action_code VARCHAR(16) NOT NULL,
                    mandate_ref VARCHAR(35) NOT NULL,
                    creditor_account VARCHAR(32),
                    currency VARCHAR(3) NOT NULL DEFAULT 'ZAR',
                    spine_state VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
                    UNIQUE (arrival_id, sequence))""");
    }

    /** One spine row at a chosen spine_state, with a creditor account for MIT to init. */
    public static void insertEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                   final String action, final String ref, final String creditorAccount,
                                   final String spineState) {
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, action_code, mandate_ref,
                    creditor_account, spine_state)
                VALUES (?,?,?,?,?,?)""",
                arrival, sequence, action, ref, creditorAccount, spineState);
    }

    public static void seedAccount(final JdbcTemplate jdbc, final String number, final String typeCode) {
        jdbc.update("""
                INSERT INTO account (account_number, account_type_code, status)
                VALUES (?,?, 'ACTIVE')
                ON CONFLICT (account_number) DO NOTHING""", number, typeCode);
    }
}
