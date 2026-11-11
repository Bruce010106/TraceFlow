package dev.traceflow;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit local operator action; public registration never claims pre-P5 projects. */
@Component
class LegacyProjectClaim implements ApplicationRunner {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final String username;
    LegacyProjectClaim(JdbcTemplate db,PlatformTransactionManager manager,
        @Value("${TRACEFLOW_LEGACY_OWNER_USERNAME:}") String username){this.db=db;this.tx=new TransactionTemplate(manager);this.username=username;}
    public void run(ApplicationArguments args){
        if(username.isBlank())return;
        tx.executeWithoutResult(ignored->{
            var users=db.queryForList("SELECT id FROM app_user WHERE username=?",AuthController.normalize(username));
            if(users.isEmpty())throw new IllegalStateException("Legacy owner must be a registered user; no projects were assigned");
            UUID user=(UUID)users.get(0).get("id");
            // A table lock also serializes two simultaneous operator startup commands.
            db.execute("LOCK TABLE project IN SHARE ROW EXCLUSIVE MODE");
            db.update("INSERT INTO project_member(project_id,user_id,role) SELECT p.id,?,'OWNER' FROM project p WHERE NOT EXISTS (SELECT 1 FROM project_member m WHERE m.project_id=p.id)",user);
        });
    }
}
