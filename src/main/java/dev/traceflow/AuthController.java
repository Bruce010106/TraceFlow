package dev.traceflow;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record Credentials(@NotBlank @Pattern(regexp="[a-zA-Z0-9][a-zA-Z0-9_-]{2,39}") String username,
        @NotNull @Size(min=12,max=64) String password){}
    private final JdbcTemplate db;private final PasswordEncoder passwords;private final AuthenticationManager authentication;
    private final ProjectAccess access;private final HttpSessionSecurityContextRepository contexts;private final HttpSessionCsrfTokenRepository tokens;
    public AuthController(JdbcTemplate db,PasswordEncoder passwords,AuthenticationManager authentication,ProjectAccess access,
        HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository tokens){
        this.db=db;this.passwords=passwords;this.authentication=authentication;this.access=access;this.contexts=contexts;this.tokens=tokens;
    }
    static String normalize(String username){return username.toLowerCase(Locale.ROOT);}
    @GetMapping("/csrf") public Object csrf(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @GetMapping("/me") public Object me(){return db.queryForMap("SELECT id,username FROM app_user WHERE id=?",access.userId());}
    @PostMapping("/register") public Object register(@Valid @RequestBody Credentials input){
        if(input.password().getBytes(StandardCharsets.UTF_8).length>72)throw new ApiException(400,"INVALID_INPUT","密码的 UTF-8 长度不能超过72字节");
        UUID id=UUID.randomUUID();String username=normalize(input.username());
        try{db.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,?)",id,username,passwords.encode(input.password()));}
        catch(DuplicateKeyException e){throw new ApiException(409,"USERNAME_TAKEN","用户名已被使用");}
        return Map.of("id",id,"username",username);
    }
    @PostMapping("/login") public Object login(@Valid @RequestBody Credentials input,HttpServletRequest request,HttpServletResponse response){
        try{
            var auth=authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(input.username(),input.password()));
            // Rotate the anonymous session and CSRF token before persisting authentication.
            if(request.getSession(false)!=null)request.changeSessionId();
            tokens.saveToken(null,request,response);
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
            return me();
        }catch(AuthenticationException e){throw new ApiException(401,"INVALID_CREDENTIALS","用户名或密码不正确");}
    }
}
