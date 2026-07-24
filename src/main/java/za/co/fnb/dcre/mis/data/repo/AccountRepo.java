package za.co.fnb.dcre.mis.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mis.data.model.AccountEntity;

import java.util.UUID;

/**
 * MIS writer over the shared dcre_man account master (single writer for the
 * creditor accounts it mints, R-04; MSR/CTV read only). Extends Repository, not
 * CrudRepository: the derived save() path cannot satisfy the table's NOT NULL
 * contract from the minimal AccountEntity, so only this targeted query is exposed.
 */
public interface AccountRepo extends Repository<AccountEntity, UUID> {

    /**
     * [SYNTHETIC-CONTRACT R-11] Idempotent creditor-account mint. account_type_code
     * is the synthetic dev default CHQ (Cheque/Current, mandates_allowed) and status
     * ACTIVE, per the dcre_man account master shape (000-man-core-bootstrap.xml);
     * account-master consolidation with the canonical type roster rides M11
     * (ruling note 1, A-62). ON CONFLICT (account_number) DO NOTHING keeps an
     * existing account untouched; the affected-row count (1 inserted / 0 conflict)
     * is what the service reads to derive the CREATED vs EXISTS verdict, so the
     * decision is atomic with the write, never a racy check-then-act.
     */
    @Modifying
    @Query("""
            INSERT INTO account (id, account_number, account_type_code, status)
            VALUES (gen_random_uuid(), :accountNumber, 'CHQ', 'ACTIVE')
            ON CONFLICT (account_number) DO NOTHING""")
    int createIfAbsent(@Param("accountNumber") String accountNumber);
}
