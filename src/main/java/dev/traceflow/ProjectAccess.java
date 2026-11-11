package dev.traceflow;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class ProjectAccess {
    private final JdbcTemplate db;
    public ProjectAccess(JdbcTemplate db){this.db=db;}
    public UUID userId(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||auth instanceof AnonymousAuthenticationToken)throw new ApiException(401,"UNAUTHENTICATED","请先登录");
        try{return UUID.fromString(auth.getName());}catch(IllegalArgumentException e){throw new ApiException(401,"UNAUTHENTICATED","请重新登录");}
    }
    public String require(UUID project,boolean write){
        var roles=db.queryForList("SELECT role FROM project_member WHERE project_id=? AND user_id=?",String.class,project,userId());
        // Conceal the existence of resources from users outside the project.
        if(roles.isEmpty())throw ApiException.missing("项目");
        String role=roles.get(0);
        if(write&&"VIEWER".equals(role))throw new ApiException(403,"FORBIDDEN","此项目为只读权限");
        return role;
    }
    public void owner(UUID project){if(!"OWNER".equals(require(project,false)))throw new ApiException(403,"FORBIDDEN","仅项目所有者可以管理成员");}
}
