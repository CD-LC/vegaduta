package com.agenticai.core.dbauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class DbAuthSettingsTest {

    @Test
    void modeParsing() {
        assertThat(DbAuthMode.parse(null)).isEqualTo(DbAuthMode.PASSWORD);
        assertThat(DbAuthMode.parse("  ")).isEqualTo(DbAuthMode.PASSWORD);
        assertThat(DbAuthMode.parse("AWS-IAM")).isEqualTo(DbAuthMode.AWS_IAM);
        assertThat(DbAuthMode.parse("azure-entra")).isEqualTo(DbAuthMode.AZURE_ENTRA);
        assertThat(DbAuthMode.parse("gcp-iam")).isEqualTo(DbAuthMode.GCP_IAM);
        assertThat(DbAuthMode.PASSWORD.usesToken()).isFalse();
        assertThat(DbAuthMode.GCP_IAM.usesToken()).isTrue();
        assertThatThrownBy(() -> DbAuthMode.parse("iam"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Allowed");
    }

    @Test
    void endpointParsing() {
        JdbcUrls.Endpoint e =
                JdbcUrls.endpoint("jdbc:postgresql://db.example.internal:6432/app?sslmode=require");
        assertThat(e.host()).isEqualTo("db.example.internal");
        assertThat(e.port()).isEqualTo(6432);
        assertThat(JdbcUrls.endpoint("jdbc:postgresql://db.example.internal/app").port())
                .isEqualTo(5432);
    }

    @Test
    void tlsIsRequiredForTokens() {
        String base = "jdbc:postgresql://h:5432/d?options=-c%20TimeZone%3DUTC";
        JdbcUrls.requireTls(base + "&sslmode=require", DbAuthMode.AWS_IAM);
        JdbcUrls.requireTls(base + "&sslmode=verify-full", DbAuthMode.AWS_IAM);
        JdbcUrls.requireTls(base + "&sslmode=verify-ca", DbAuthMode.AWS_IAM);
        JdbcUrls.requireTls(base + "&ssl=true", DbAuthMode.AZURE_ENTRA);
        for (String bad :
                new String[] {
                    base,
                    base + "&sslmode=disable",
                    base + "&sslmode=prefer",
                    base + "&sslmode=allow",
                    base + "&ssl=false",
                    // pgjdbc matches parameter names case-sensitively: this one is ignored by it.
                    base + "&SSLMODE=verify-ca",
                    base + "&SSL=true"
                }) {
            assertThatThrownBy(() -> JdbcUrls.requireTls(bad, DbAuthMode.GCP_IAM))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sslmode=require")
                    .hasMessageContaining("gcp-iam");
        }
    }

    /**
     * The guard must agree with what the real pgjdbc driver would do with the same URL: accept
     * exactly the URLs for which the driver negotiates TLS (a false accept sends the token in the
     * clear, a false reject blocks a working setup).
     */
    @Test
    void tlsGuardAgreesWithThePgjdbcDriver() throws Exception {
        String[] queries = {
            "",
            "sslmode=require",
            "SSLMODE=verify-ca",
            "sslmode=REQUIRE",
            "sslmode=Verify-Full",
            "sslmode=verify-ca",
            "sslmode=disable",
            "sslmode=prefer",
            "sslmode=allow",
            "sslmode=",
            "sslmode=bogus",
            "ssl=true",
            "ssl=TRUE",
            "ssl",
            "ssl=",
            "ssl=false",
            "ssl=1",
            "SSL=true",
            "sslmode=require&sslmode=disable",
            "sslmode=disable&sslmode=require",
            "ssl=true&sslmode=disable",
            "ssl=false&sslmode=require",
            "ssl=true&sslmode=prefer",
            "sslmode=%72equire",
            "%73slmode=require",
            "options=-c%20TimeZone%3DUTC&sslmode=require",
            "sslmode=require&options=-c%20TimeZone%3DUTC"
        };
        for (String q : queries) {
            String url = "jdbc:postgresql://h:5432/d" + (q.isEmpty() ? "" : "?" + q);
            boolean driverEncrypts;
            try {
                Properties parsed = org.postgresql.Driver.parseURL(url, null);
                driverEncrypts = org.postgresql.jdbc.SslMode.of(parsed).requireEncryption();
            } catch (org.postgresql.util.PSQLException | IllegalArgumentException e) {
                driverEncrypts = false; // the driver itself refuses such a URL
            }
            boolean guardAccepts;
            try {
                JdbcUrls.requireTls(url, DbAuthMode.AWS_IAM);
                guardAccepts = true;
            } catch (IllegalStateException e) {
                guardAccepts = false;
            }
            assertThat(guardAccepts)
                    .as("query [%s]: driver encrypts=%s", q, driverEncrypts)
                    .isEqualTo(driverEncrypts);
        }
    }

    @Test
    void nonPostgresUrlIsRejected() {
        assertThatThrownBy(() -> JdbcUrls.endpoint("jdbc:mysql://h/d"))
                .isInstanceOf(IllegalStateException.class);
    }
}
