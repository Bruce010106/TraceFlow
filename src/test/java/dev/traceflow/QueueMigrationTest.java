package dev.traceflow;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class QueueMigrationTest {
    @Test void migratesP3HistoryAndRequeuesInterruptedJobs(){
        var pg=TestDatabase.pg();
        var admin=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl("postgres","postgres")+"&sslmode=disable&gssEncMode=disable","postgres",""));
        String database="traceflow_migration_"+UUID.randomUUID().toString().replace("-","");
        if(!database.matches("traceflow_migration_[0-9a-f]{32}"))throw new IllegalStateException("Unsafe test database name");
        admin.execute("CREATE DATABASE "+database);
        try{
            String url=pg.getJdbcUrl("postgres",database)+"&sslmode=disable&gssEncMode=disable";
            Flyway.configure().dataSource(url,"postgres","").target("2").load().migrate();
            var db=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",""));
            UUID project=UUID.randomUUID(),first=UUID.randomUUID(),interrupted=UUID.randomUUID();
            UUID failed=UUID.randomUUID(),succeeded=UUID.randomUUID(),running=UUID.randomUUID();
            db.update("INSERT INTO project(id,name,timezone) VALUES(?,?,?)",project,"legacy","Asia/Hong_Kong");
            for(UUID doc:new UUID[]{first,interrupted})
                db.update("INSERT INTO source_document(id,project_id,title,content,content_hash,reference_date,timezone) VALUES(?,?,?,?,?,?,?)",
                    doc,project,"legacy","原文-"+doc,doc.toString().replace("-","").repeat(2),LocalDate.of(2026,11,11),"Asia/Hong_Kong");
            db.update("INSERT INTO extraction_job(id,document_id,status,attempt_count,model,prompt_version,input_snapshot_json,error_code) VALUES(?,?,'FAILED',1,'mock-v1','mock-v1','{\"tasks\":[]}'::jsonb,'MODEL_TIMEOUT')",failed,first);
            db.update("INSERT INTO extraction_job(id,document_id,status,attempt_count,model,prompt_version,input_snapshot_json,output_json) VALUES(?,?,'SUCCEEDED',2,'mock-v1','mock-v1','{\"tasks\":[]}'::jsonb,'{\"proposals\":[]}'::jsonb)",succeeded,first);
            db.update("INSERT INTO extraction_job(id,document_id,status,attempt_count,model,prompt_version,input_snapshot_json) VALUES(?,?,'RUNNING',1,'mock-v1','mock-v1','{\"tasks\":[]}'::jsonb)",running,interrupted);
            Flyway.configure().dataSource(url,"postgres","").load().migrate();
            var oldSuccess=db.queryForMap("SELECT * FROM extraction_job WHERE id=?",succeeded);
            assertEquals(2,oldSuccess.get("generation"));assertEquals(1,oldSuccess.get("attempt_count"));
            assertEquals("SUCCEEDED",oldSuccess.get("status"));
            var recovered=db.queryForMap("SELECT * FROM extraction_job WHERE id=?",running);
            assertEquals("QUEUED",recovered.get("status"));assertEquals(0,recovered.get("attempt_count"));
            assertNotNull(recovered.get("next_run_at"));assertNull(recovered.get("lease_token"));
            assertEquals(2,db.queryForObject("SELECT count(*) FROM extraction_attempt",Integer.class));
            assertEquals("原文-"+interrupted,db.queryForObject("SELECT content FROM source_document WHERE id=?",String.class,interrupted));
        }finally{admin.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }
}
