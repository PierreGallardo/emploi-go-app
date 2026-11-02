package fr.emploigo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One package reads and writes {@code auth_accounts}, and one class states who may authenticate.
 */
class AccountTableAccessTest {
    private static final Path MAIN = Path.of("src/main/java");
    private static final String TABLE = "auth_accounts";
    private static final String SQL_ROLE = "'JEUNE'";
    private static final String INFRASTRUCTURE = "fr/emploigo/auth/infrastructure/";
    private static final String ACCOUNT_PACKAGE = INFRASTRUCTURE + "account/";

    @Test
    void onlyTheAccountPackageNamesTheAccountsTable() {
        assertThat(SourceRules.javaFiles(MAIN)).as("Java files under " + MAIN).isPositive();
        assertThat(SourceRules.filesContaining(MAIN, TABLE))
                .as("production files that name " + TABLE)
                .isNotEmpty();
        assertThat(filesOutsideAccountPackage(MAIN))
                .as("production files outside " + ACCOUNT_PACKAGE + " that name " + TABLE)
                .isEmpty();
    }

    @Test
    void noSqlRestatesTheRoleThatMayAuthenticate() {
        assertThat(SourceRules.filesContaining(MAIN, SQL_ROLE))
                .as("production files with the SQL literal " + SQL_ROLE)
                .isEmpty();
    }

    @Test
    void aFileOutsideTheAccountPackageThatNamesTheTableIsFound(@TempDir Path root)
            throws IOException {
        write(root, ACCOUNT_PACKAGE + "SpringDataAccountRepository.java", "FROM auth_accounts");
        write(root, INFRASTRUCTURE + "OtherRepository.java", "JOIN auth_accounts a ON a.id = 1");
        write(root, INFRASTRUCTURE + "Quiet.java", "SELECT 1 FROM auth_reconnections");

        assertThat(filesOutsideAccountPackage(root))
                .as("files outside the package that name " + TABLE)
                .containsExactly(INFRASTRUCTURE + "OtherRepository.java");
    }

    @Test
    void aSqlRoleLiteralIsFoundWhereverItIsWritten(@TempDir Path root) throws IOException {
        write(root, "Query.java", "\"WHERE a.role = 'JEUNE'\"");
        write(root, "Route.java", "hasRole(\"JEUNE\")");

        assertThat(SourceRules.filesContaining(root, SQL_ROLE))
                .as("files with the SQL literal")
                .containsExactly("Query.java");
    }

    @Test
    void theCaseOfTheTableNameDoesNotHideIt(@TempDir Path root) throws IOException {
        write(root, "Shout.java", "SELECT 1 FROM AUTH_ACCOUNTS");

        assertThat(SourceRules.filesContaining(root, TABLE))
                .as("files")
                .containsExactly("Shout.java");
    }

    @Test
    void sourcesThatAreNotJavaAreIgnored(@TempDir Path root) throws IOException {
        write(root, "notes.txt", "auth_accounts");

        assertThat(SourceRules.filesContaining(root, TABLE)).as("files").isEmpty();
        assertThat(SourceRules.javaFiles(root)).as("Java files").isZero();
    }

    /** The files that name the table, below {@code root}, outside the package that owns it. */
    private static List<String> filesOutsideAccountPackage(Path root) {
        return SourceRules.filesContaining(root, TABLE).stream()
                .filter(file -> !file.startsWith(ACCOUNT_PACKAGE))
                .toList();
    }

    private static void write(Path root, String name, String content) throws IOException {
        var file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
