package io.portfolio;

import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Configuration @Profile("google")
class GoogleProvider {
    @Bean ClientRegistrationRepository registration(@Value("${app.google-id}") String id,@Value("${app.google-secret}") String secret) {
        if(id.isBlank()||secret.isBlank()) throw new IllegalStateException("Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET for the google profile.");
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId(id).clientSecret(secret).build());
    }
}

@Component
class GoogleIdentity {
    private record Pending(Account user,Instant expiration) {}
    final Auth auth;
    private final ConcurrentHashMap<String,Pending> pending=new ConcurrentHashMap<>();
    GoogleIdentity(Auth auth) { this.auth=auth; }

    void success(HttpServletRequest request,HttpServletResponse response,Authentication authentication) throws IOException {
        if(request.getSession(false)!=null) request.getSession(false).invalidate();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        if(!(authentication.getPrincipal() instanceof OidcUser oidc)||!Boolean.TRUE.equals(oidc.getEmailVerified())||oidc.getEmail()==null) {
            response.sendRedirect("/?oauth_error=unverified"); return;
        }
        String email=Auth.email(oidc.getEmail());
        if(email.length()>254) { response.sendRedirect("/?oauth_error=unverified"); return; }
        Account account=auth.store.byEmail(email).orElse(null);
        if(account!=null&&(account.hash()!=null||!account.role().equals("RIDER"))) {
            response.sendRedirect("/?oauth_error=password"); return;
        }
        if(account==null) {
            String name=oidc.getFullName()==null||oidc.getFullName().isBlank()?"Rider":oidc.getFullName().strip();
            account=new Account(UUID.randomUUID().toString(),email,name.substring(0,Math.min(70,name.length())),null,"RIDER");
            try { auth.store.store(account); }
            catch(org.springframework.dao.DataIntegrityViolationException duplicate) { response.sendRedirect("/?oauth_error=sign-in");return; }
        }
        Instant now=Instant.now();
        pending.entrySet().removeIf(e->e.getValue().expiration().isBefore(now));
        if(pending.size()>=500) { response.sendRedirect("/?oauth_error=sign-in");return; }
        String state=UUID.randomUUID().toString();
        pending.put(state,new Pending(account,now.plusSeconds(60)));
        response.sendRedirect("/?oauth="+state);
    }

    AuthResult exchange(String code) {
        var item=code==null?null:pending.remove(code);
        if(item==null||item.expiration().isBefore(Instant.now())) throw Problems.bad("Google sign-in expired. Try again.");
        return auth.issue(item.user());
    }
}
@RestController
class GoogleExchange {
    record Code(String code) {}
    final GoogleIdentity google;
    GoogleExchange(GoogleIdentity google) {this.google=google;}
    @PostMapping("/api/auth/google/exchange") AuthResult exchange(@RequestBody Code body) { return google.exchange(body.code()); }
}
