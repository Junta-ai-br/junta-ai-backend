package br.com.juntaai.service;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Runs only against the explicitly disposable loopback database. */
@EnabledIfEnvironmentVariable(named = "P0_DISPOSABLE_DB", matches = "true")
class CategoryStandardKeyMigrationPostgresTest {
    @Test void additiveMigrationPreservesLegacyAndEnforcesKeys() throws Exception {
        String url = System.getenv("DB_URL");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/junta_p0_disposable_[a-z0-9_]+");
        String user = System.getenv("DB_USER"), password = System.getenv("DB_PASSWORD");
        String schema = "category_identity_" + UUID.randomUUID().toString().replace("-", "");
        var config = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema);
        config.target(MigrationVersion.fromVersion("12")).load().migrate();
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            c.setSchema(schema);
            try (Statement s = c.createStatement()) {
                s.execute("insert into users(id,name,email) values ('00000000-0000-4000-8000-000000000001','Synthetic','one@test.invalid'), ('00000000-0000-4000-8000-000000000002','Synthetic','two@test.invalid')");
                s.execute("insert into categories(id,user_id,name,type) values ('00000000-0000-4000-8000-000000000003','00000000-0000-4000-8000-000000000001','Legacy renamed','EXPENSE')");
                s.execute("insert into transactions(id,user_id,category_id,type,amount,transaction_date) values ('00000000-0000-4000-8000-000000000004','00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000003','EXPENSE',351.01,'2026-11-01')");
                config.target(MigrationVersion.LATEST).load().migrate();
                try (ResultSet rs = s.executeQuery("select c.standard_key,c.name,c.type,t.amount,t.category_id,u.initial_categories_initialized from categories c join transactions t on t.category_id=c.id join users u on u.id=c.user_id")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getObject(1)).isNull();
                    assertThat(rs.getString(2)).isEqualTo("Legacy renamed");
                    assertThat(rs.getString(3)).isEqualTo("EXPENSE");
                    assertThat(rs.getBigDecimal(4)).isEqualByComparingTo("351.01");
                    assertThat(rs.getString(5)).isEqualTo("00000000-0000-4000-8000-000000000003");
                    assertThat(rs.getBoolean(6)).isFalse();
                }
                String insert = "insert into categories(user_id,name,type,standard_key) values ('%s','%s','EXPENSE',%s)";
                String owner = "00000000-0000-4000-8000-000000000001", other = "00000000-0000-4000-8000-000000000002";
                s.execute(insert.formatted(owner, "Custom one", "NULL"));
                s.execute(insert.formatted(owner, "Custom two", "NULL"));
                s.execute(insert.formatted(owner, "Food", "'FOOD'"));
                s.execute(insert.formatted(other, "Food", "'FOOD'"));
                assertThatThrownBy(() -> s.execute(insert.formatted(owner, "Duplicate", "'FOOD'"))).isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("23505");
                assertThatThrownBy(() -> s.execute(insert.formatted(owner, "Invalid", "'INVALID'"))).isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("23514");
                assertThatThrownBy(() -> s.execute("update categories set standard_key='HEALTH' where name='Food'" )).isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("23514");
                assertThatThrownBy(() -> s.execute("update categories set standard_key=NULL where name='Food'" )).isInstanceOf(SQLException.class);
                // Guard permits explicit future adoption, but this test uses synthetic data only.
                s.execute("update categories set standard_key='HEALTH' where name='Custom one'");
                s.execute("update categories set name='Still editable' where name='Food'");
                assertThatThrownBy(() -> s.execute("delete from categories where id='00000000-0000-4000-8000-000000000003'")).isInstanceOf(SQLException.class).extracting(e -> ((SQLException)e).getSQLState()).isEqualTo("23001");
                c.setAutoCommit(false);
                s.execute(insert.formatted(owner, "Rolled back", "'BONUSES'"));
                c.rollback(); c.setAutoCommit(true);
                try (ResultSet rs = s.executeQuery("select count(*) from categories where standard_key='BONUSES'")) { rs.next(); assertThat(rs.getInt(1)).isZero(); }
                assertThat(schema).startsWith("category_identity_").matches("category_identity_[a-f0-9]+");
                s.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
