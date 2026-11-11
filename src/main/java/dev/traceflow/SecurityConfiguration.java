package dev.traceflow;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
    @Bean AuthenticationManager authenticationManager(JdbcTemplate db,PasswordEncoder encoder){
        UserDetailsService users=username->{
            var rows=db.queryForList("SELECT id,password_hash FROM app_user WHERE username=?",AuthController.normalize(username));
            if(rows.isEmpty())throw new UsernameNotFoundException("Invalid credentials");
            var row=rows.get(0);
            return User.withUsername(row.get("id").toString()).password(row.get("password_hash").toString()).authorities("USER").build();
        };
        var provider=new DaoAuthenticationProvider(users);provider.setPasswordEncoder(encoder);
        return new ProviderManager(List.of(provider));
    }
    @Bean HttpSessionSecurityContextRepository securityContexts(){return new HttpSessionSecurityContextRepository();}
    @Bean HttpSessionCsrfTokenRepository csrfTokens(){return new HttpSessionCsrfTokenRepository();}
    @Bean SecurityFilterChain security(HttpSecurity http,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository tokens)throws Exception{
        return http.securityContext(c->c.securityContextRepository(contexts))
            .csrf(c->c.csrfTokenRepository(tokens))
            .requestCache(c->c.disable())
            .authorizeHttpRequests(c->c.dispatcherTypeMatchers(DispatcherType.FORWARD,DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/csrf","/api/auth/login","/api/auth/register","/api/health").permitAll()
                .requestMatchers("/api/**").authenticated().anyRequest().permitAll())
            .exceptionHandling(c->c.authenticationEntryPoint((request,response,error)->error(response,401,"UNAUTHENTICATED","请先登录"))
                .accessDeniedHandler((request,response,error)->error(response,403,"CSRF_INVALID","页面凭据已过期，请刷新后重试")))
            .logout(c->c.logoutUrl("/api/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID").logoutSuccessHandler((request,response,auth)->{response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"loggedOut\":true}");}))
            .build();
    }
    static void error(HttpServletResponse response,int status,String code,String message)throws IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");
    }
}
