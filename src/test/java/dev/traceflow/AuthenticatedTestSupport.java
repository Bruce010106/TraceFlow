package dev.traceflow;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Existing regressions still pass through the actual security chain with a known project owner. */
abstract class AuthenticatedTestSupport {
    static final UUID TEST_USER=UUID.fromString("a412904a-e081-4ca0-9dbf-ce7048c897e9");
    static final org.springframework.security.core.userdetails.UserDetails PRINCIPAL=User.withUsername(TEST_USER.toString()).password("unused").authorities("USER").build();
    @Autowired WebApplicationContext web;
    @Autowired JdbcTemplate authDb;
    MockMvc authenticatedMvc(){
        // Scoped to tests: direct concurrent service regressions explicitly inherit this test identity.
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
        authDb.update("INSERT INTO app_user(id,username,password_hash) VALUES(?,?,'unused') ON CONFLICT(id) DO NOTHING",TEST_USER,"regression-owner");
        restoreIdentity();
        return MockMvcBuilders.webAppContextSetup(web).apply(springSecurity())
            .defaultRequest(get("/").with(user(PRINCIPAL)).with(csrf())).build();
    }
    void restoreIdentity(){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL,"unused",PRINCIPAL.getAuthorities()));}
    @AfterEach void clearIdentity(){SecurityContextHolder.clearContext();SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_THREADLOCAL);}
}
