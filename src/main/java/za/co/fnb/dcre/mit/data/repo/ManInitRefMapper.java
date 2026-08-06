package za.co.fnb.dcre.mit.data.repo;

import org.springframework.jdbc.core.RowMapper;
import za.co.fnb.dcre.mit.data.model.ManInitRef;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ManInitRefMapper implements RowMapper<ManInitRef> {

    @Override
    public ManInitRef mapRow(final ResultSet r, final int rowNum) throws SQLException {
        return new ManInitRef(r.getInt("sequence"), r.getString("creditor_account"));
    }
}
