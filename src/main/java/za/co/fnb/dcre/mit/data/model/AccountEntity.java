package za.co.fnb.dcre.mit.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Minimal aggregate over the shared dcre_man {@code account} master. MIT only
 * ever touches it through {@link za.co.fnb.dcre.mit.data.repo.AccountRepo}'s
 * targeted create-if-absent query, so this anchor carries only the identity.
 */
@Table("account")
public class AccountEntity {

    @Id
    private UUID id;
    private String accountNumber;

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }
}
