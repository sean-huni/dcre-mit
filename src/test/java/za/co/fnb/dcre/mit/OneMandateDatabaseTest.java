package za.co.fnb.dcre.mit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-107 repair 2, the WRITER half. MIT mints creditor accounts into
 * {@code dcre_man.account} and MRV reads them from the same database. This test exists
 * because that pair was briefly broken from the READER end alone: MRV was repointed at a
 * separate account database while this writer stayed put, so MIT reported a successful
 * mint and MRV answered {@code FAIL_ACCOUNT_NOT_FOUND} for the account it had just
 * created. Nothing errored. No suite went red. Each half was internally consistent and
 * only the pair was wrong.
 *
 * <p>MRV carries the mirror of this test. A guard on one side of a writer/reader pair
 * only catches a migration that starts on that side, and this one started on the other.
 * If the account master does move out of dcre_man, BOTH tests fail in the same commit,
 * which is the property worth having: the move becomes a decision somebody makes, rather
 * than a state the estate drifts into one end at a time.
 */
class OneMandateDatabaseTest {

    private static final String[] SECOND_STORE_TOKENS = {
            "dcre_acs", "acc_mrv_view", "acc_type_mrv_view", "acc_mit_view",
            "accounts-db-url", "accounts-db-user", "accounts-db-password",
            "AccountsDatasourceConfig", "AccountReferenceDao"};

    @Test
    void theAccountWriteTargetsTheBareRelationInTheServiceDatabase() throws Exception {
        String repo = Files.readString(
                Path.of("src/main/java/za/co/fnb/dcre/mit/data/repo/AccountRepo.java"));
        assertThat(repo)
                .as("MIT is the mandates account writer and writes dcre_man.account")
                .contains("INSERT INTO account (")
                .doesNotContain("dcre_acs");
    }

    @Test
    void theConfiguredDatabaseIsDcreMan() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"))
                .replaceAll("(?m)^\\s*#.*$", " ");
        assertThat(yml)
                .as("one database for the mandates family: the writer writes where the reader reads")
                .contains("${DCRE_DB_URL:jdbc:postgresql://localhost:26257/dcre_man");
    }

    @Test
    void noShippedFileReachesASecondAccountStore() throws Exception {
        assertThat(offenders("src/main/java", SECOND_STORE_TOKENS)).isEmpty();
        assertThat(offenders("src/main/resources", SECOND_STORE_TOKENS)).isEmpty();
        // Controls: both walks read real files, so the two emptiness results above are
        // facts about the tree rather than about a walk that visited nothing.
        assertThat(offenders("src/main/java", "AccountRepo")).isNotEmpty();
        assertThat(offenders("src/main/resources", "account")).isNotEmpty();
    }

    /**
     * Read off the annotation, not the file text: an extra datasource config reintroduced
     * by {@code @Import} is a live second connection in every Spring context.
     */
    @Test
    void theApplicationImportsExactlyThePlatformDatasourceConfigs() {
        List<String> imported = Arrays.stream(MitApplication.class.getAnnotation(Import.class).value())
                .map(Class::getSimpleName)
                .toList();
        assertThat(imported)
                .containsExactlyInAnyOrder("JdbcConfig", "BatchJdbcConfig", "HeartbeatDatasourceConfig");
    }

    private static List<Path> offenders(final String root, final String... tokens) throws Exception {
        try (var paths = Files.walk(Path.of(root))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> containsAny(path, tokens))
                    .toList();
        }
    }

    private static boolean containsAny(final Path path, final String... tokens) {
        try {
            String text = strip(path, Files.readString(path));
            return Arrays.stream(tokens).anyMatch(text::contains);
        } catch (Exception e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }

    /**
     * Comments out, BY FILE TYPE. Applying Java's {@code //} rule to a yml file truncates
     * every line at the {@code //} of a JDBC URL, which silently deletes the primary
     * datasource line from the scan and turns every absence assertion around it into a
     * pass for the wrong reason. Caught by a control while writing the MRV twin.
     */
    private static String strip(final Path path, final String text) {
        String name = path.getFileName().toString();
        if (name.endsWith(".java")) {
            return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
        }
        if (name.endsWith(".xml")) {
            return text.replaceAll("(?s)<!--.*?-->", " ");
        }
        if (name.endsWith(".yml")) {
            return text.replaceAll("(?m)^\\s*#.*$", " ");
        }
        return text;
    }
}
