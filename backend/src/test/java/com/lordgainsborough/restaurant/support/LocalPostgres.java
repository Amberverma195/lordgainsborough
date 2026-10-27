package com.lordgainsborough.restaurant.support;

import java.nio.file.*;
import java.sql.*;
import java.util.UUID;

/** No containers. Mutating tests require explicit access to an isolated loopback cluster. */
public final class LocalPostgres implements AutoCloseable {
    private static String verifiedPort;
    public static synchronized String requirePort() {
        String port = System.getProperty("test.postgres.port");
        if (port == null || !port.matches("[0-9]{1,5}"))
            throw new IllegalStateException("Configure an isolated PostgreSQL 17 cluster with -Dtest.postgres.port=<port>");
        if (port.equals(verifiedPort)) return port;
        try (var c=DriverManager.getConnection(url(port,"postgres"),"postgres","postgres"); var s=c.createStatement()) {
            if (c.getMetaData().getDatabaseMajorVersion()!=17) throw new IllegalStateException("Tests require PostgreSQL 17");
            try (var rows=s.executeQuery("SHOW data_directory")) {
                rows.next(); var directory=Path.of(rows.getString(1));
                boolean previouslyOwned = directory.getFileName().toString().equals("data")
                        && directory.getParent().getFileName().toString().matches("lgb_(?:pg017|test)_[a-f0-9]{32}")
                        && Files.readString(directory.resolve("PG_VERSION")).trim().equals("17");
                if (!previouslyOwned && !Boolean.getBoolean("test.postgres.isolated"))
                    throw new IllegalStateException("Refusing mutations: explicitly attest a dedicated test cluster with -Dtest.postgres.isolated=true");
            }
            verifiedPort=port; return port;
        } catch (Exception e) { throw new IllegalStateException("Isolated PostgreSQL verification failed",e); }
    }
    private static String url(String port,String database) { return "jdbc:postgresql://127.0.0.1:"+port+"/"+database+"?sslmode=disable"; }
    private final String database="lgb_test_"+UUID.randomUUID().toString().replace("-", "");
    private final String port;
    private boolean owned;
    public LocalPostgres() {
        port=requirePort();
        try(var c=DriverManager.getConnection(url(port,"postgres"),"postgres","postgres");var s=c.createStatement()) {
            s.executeUpdate("CREATE DATABASE "+database); owned=true;
        } catch(SQLException e) { throw new IllegalStateException("Unable to create owned test database",e); }
    }
    public String jdbcUrl() { return url(port,database); }
    public void close() {
        if (!owned) return;
        try(var c=DriverManager.getConnection(url(port,"postgres"),"postgres","postgres");var s=c.createStatement()) {
            s.executeUpdate("DROP DATABASE "+database+" WITH (FORCE)"); owned=false;
        } catch(SQLException e) { throw new IllegalStateException("Owned test database cleanup failed",e); }
    }
}
