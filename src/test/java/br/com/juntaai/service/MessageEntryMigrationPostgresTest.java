package br.com.juntaai.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Upgrade is exercised in a separate database on the same disposable PostgreSQL 16 cluster. */
@EnabledIfEnvironmentVariable(named = "I3_DISPOSABLE_DB", matches = "true")
class MessageEntryMigrationPostgresTest {
    @Test void v5UpgradePreservesExistingMessagesAndReceipt() throws Exception {
        String baseUrl = System.getenv("DB_URL"), username = System.getenv("DB_USER"), password = System.getenv("DB_PASSWORD");
        assertThat(baseUrl).contains("junta_i3_disposable_");
        String name = "junta_i3_disposable_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(baseUrl, username, password); var sql = connection.createStatement()) {
            try (var version = sql.executeQuery("show server_version_num")) {
                assertThat(version.next()).isTrue(); assertThat(version.getInt(1)).isBetween(160000, 169999);
            }
            sql.execute("CREATE DATABASE " + name);
        }
        String url = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + name;
        Flyway.configure().dataSource(url, username, password).target("5").load().migrate();
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID(), message = UUID.randomUUID();
        String action = UUID.randomUUID().toString();
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("insert into users(id,name,email) values(?, 'Synthetic',?)")) {
                insert.setObject(1, user); insert.setString(2, user + "@test.invalid"); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("insert into conversations(id,user_id) values(?,?)")) {
                insert.setObject(1, conversation); insert.setObject(2, user); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("insert into messages(id,conversation_id,role,content) values(?,?,'USER','legacy synthetic')")) {
                insert.setObject(1, message); insert.setObject(2, conversation); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    insert into action_executions(action_id,user_id,conversation_id,user_message_id,request_id,action_json,receipt_json,status)
                    values(?,?,?,?, 'legacy-request','legacy-action','legacy-receipt','UNAVAILABLE')
                    """)) {
                insert.setString(1, action); insert.setObject(2, user); insert.setObject(3, conversation); insert.setObject(4, message); insert.executeUpdate();
            }
            connection.commit();
        }
        var latest = Flyway.configure().dataSource(url, username, password).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        try (var connection = DriverManager.getConnection(url, username, password); var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("select content from messages")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("legacy synthetic");
            }
            try (var result = sql.executeQuery("select action_json,receipt_json,user_message_id from action_executions")) {
                assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("legacy-action");
                assertThat(result.getString(2)).isEqualTo("legacy-receipt"); assertThat(result.getObject(3, UUID.class)).isEqualTo(message);
            }
            try (var result = sql.executeQuery("select count(*) from conversation_message_entries")) {
                assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isZero(); // No inferred legacy identity.
            }
        }
    }
}
