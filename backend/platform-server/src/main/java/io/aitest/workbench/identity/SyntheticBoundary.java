package io.aitest.workbench.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Arrays;
import io.aitest.workbench.infrastructure.Store;

@Configuration
public class SyntheticBoundary {
    public SyntheticBoundary(Environment environment,Store db) {
        validateTarget(environment);
        if(!"isolated_synthetic_only".equals(db.one("SELECT mode_name FROM synthetic_boundary WHERE id=1").get("mode_name")))throw new IllegalStateException("Synthetic database marker required.");
        io.aitest.workbench.bootstrap.SchemaReadiness.platform(db.jdbc==null?null:db.jdbc.getDataSource());
    }
    public static void validateTarget(Environment environment) {
        if(!Arrays.asList(environment.getActiveProfiles()).equals(java.util.List.of("synthetic")))
            throw new IllegalStateException("D1/D2 requires explicit synthetic profile; no production identity is implemented.");
        if(!"127.0.0.1".equals(environment.getProperty("server.address","127.0.0.1")))throw new IllegalStateException("Synthetic identity must bind IPv4 loopback only.");
        try {
            String jdbc=environment.getRequiredProperty("spring.datasource.url");
            if(!jdbc.startsWith("jdbc:mysql://"))throw new IllegalArgumentException();
            var target=java.net.URI.create(jdbc.substring(5));
            if(!"127.0.0.1".equals(target.getHost())||target.getUserInfo()!=null||target.getFragment()!=null||!"/workbench_platform".equals(target.getPath()))throw new IllegalArgumentException();
        }catch(IllegalArgumentException e){throw new IllegalStateException("Synthetic identity requires the isolated loopback platform database.");}
    }
    @Bean FilterRegistrationBean<OncePerRequestFilter> syntheticFilter(Scope scope) {
        OncePerRequestFilter filter=new OncePerRequestFilter(){
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
                if(!req.getRequestURI().startsWith("/api/")){chain.doFilter(req,res);return;}
                res.setHeader("Cache-Control","no-store");
                String origin=req.getHeader("Origin");
                if(origin!=null && !origin.equals(req.getScheme()+"://"+req.getHeader("Host"))){res.setStatus(403);res.setContentType("application/json");res.getWriter().write("{\"error\":{\"code\":\"ORIGIN_DENIED\"}}");return;}
                try {req.setAttribute("actor",scope.actor(req.getHeader("X-Test-Subject")));chain.doFilter(req,res);}
                catch(io.aitest.workbench.infrastructure.Store.Problem p){res.setStatus(p.status);res.setContentType("application/json");res.getWriter().write("{\"error\":{\"code\":\""+p.code+"\"}}");}
            }
        };
        var bean=new FilterRegistrationBean<>(filter);bean.setOrder(-100);return bean;
    }
}
