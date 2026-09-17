package com.abheenash.triage.core;

/** Which database was examined. Recorded in the report so a stored result stays interpretable. */
public record TargetInfo(
        String host,
        int port,
        String database,
        String user,
        String serverVersion,
        String applicationName) {

    /** Never includes a password; there is nowhere in this type to put one. */
    public String describe() {
        return user + "@" + host + ":" + port + "/" + database;
    }
}
