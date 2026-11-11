package dev.traceflow;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

/** Ephemeral localhost demo. Database contents are discarded on exit. */
public final class LocalDev {
    public static void main(String[] args) throws Exception {
        var postgres=EmbeddedPostgres.builder().setPort(0).setPGStartupWait(java.time.Duration.ofSeconds(120)).setServerConfig("max_connections","20").setServerConfig("shared_buffers","16MB").setLocaleConfig("locale","C").setConnectConfig("sslmode","disable").setConnectConfig("gssEncMode","disable").start();
        System.setProperty("spring.datasource.url",postgres.getJdbcUrl("postgres","postgres")+"&sslmode=disable&gssEncMode=disable");
        System.setProperty("spring.datasource.username","postgres");
        System.setProperty("spring.datasource.password","");
        try {
            SpringApplication.run(TraceFlowApplication.class,args);
        } catch (RuntimeException failure) {
            postgres.close();
            throw failure;
        }
    }
}
