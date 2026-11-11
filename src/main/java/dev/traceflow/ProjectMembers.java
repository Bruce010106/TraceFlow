package dev.traceflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
public class ProjectMembers {
    private final JdbcTemplate db;private final ProjectAccess access;
    public ProjectMembers(JdbcTemplate db,ProjectAccess access){this.db=db;this.access=access;}
    public List<Map<String,Object>> list(UUID project){
        access.require(project,false);
        return db.queryForList("SELECT u.id,u.username,m.role FROM project_member m JOIN app_user u ON u.id=m.user_id WHERE m.project_id=? ORDER BY m.created_at,u.username",project);
    }
    private void lockOwner(UUID project){
        db.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE",project);access.owner(project);
    }
    @Transactional public Object put(UUID project,String username,String role){
        lockOwner(project);
        if(!List.of("EDITOR","VIEWER").contains(role))throw new ApiException(400,"INVALID_INPUT","成员角色必须为 EDITOR 或 VIEWER");
        var users=db.queryForList("SELECT id FROM app_user WHERE username=?",AuthController.normalize(username));
        if(users.isEmpty())throw ApiException.missing("已注册用户");
        Object user=users.get(0).get("id");
        if(user.equals(access.userId()))throw new ApiException(409,"OWNER_REQUIRED","所有者不能修改自己的角色");
        db.update("INSERT INTO project_member(project_id,user_id,role) VALUES(?,?,?) ON CONFLICT(project_id,user_id) DO UPDATE SET role=EXCLUDED.role",project,user,role);
        return list(project);
    }
    @Transactional public Object remove(UUID project,String username){
        lockOwner(project);
        if(db.queryForObject("SELECT username FROM app_user WHERE id=?",String.class,access.userId()).equals(AuthController.normalize(username)))
            throw new ApiException(409,"OWNER_REQUIRED","所有者不能移除自己");
        db.update("DELETE FROM project_member WHERE project_id=? AND user_id IN (SELECT id FROM app_user WHERE username=?)",project,AuthController.normalize(username));
        return list(project);
    }
}

@RestController
@RequestMapping("/api/projects/{project}/members")
class ProjectMembersController {
    record MemberInput(@jakarta.validation.constraints.NotNull @Pattern(regexp="EDITOR|VIEWER") String role){}
    private final ProjectMembers members;
    ProjectMembersController(ProjectMembers members){this.members=members;}
    @GetMapping Object list(@PathVariable UUID project){return members.list(project);}
    @PutMapping("/{username}") Object put(@PathVariable UUID project,@PathVariable String username,@Valid @RequestBody MemberInput input){return members.put(project,username,input.role());}
    @DeleteMapping("/{username}") Object remove(@PathVariable UUID project,@PathVariable String username){return members.remove(project,username);}
}
