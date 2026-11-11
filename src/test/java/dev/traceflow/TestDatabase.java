package dev.traceflow;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One isolated PostgreSQL per test JVM; Spring contexts close before the JVM shuts it down. */
final class TestDatabase {
    private static final EmbeddedPostgres PG=start();
    private static EmbeddedPostgres start(){
        try{
            var pg=EmbeddedPostgres.builder().setPort(0).setPGStartupWait(java.time.Duration.ofSeconds(120))
                .setServerConfig("max_connections","30").setServerConfig("shared_buffers","16MB").setLocaleConfig("locale","C")
                .setConnectConfig("sslmode","disable").setConnectConfig("gssEncMode","disable").start();
            Runtime.getRuntime().addShutdownHook(new Thread(()->{try{pg.close();}catch(Exception ignored){}}));
            return pg;
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static EmbeddedPostgres pg(){return PG;}
    static void register(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",()->PG.getJdbcUrl("postgres","postgres")+"&sslmode=disable&gssEncMode=disable");
        registry.add("spring.datasource.username",()->"postgres");registry.add("spring.datasource.password",()->"");
    }
}
