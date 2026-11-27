package io.portfolio;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

record Account(String id, String email, String name, String hash, String role) {}
record LoginSession(String id, String user, Instant expires) {}
record Person(String id, String email, String name, String role) {
    static Person view(Account value) { return new Person(value.id(), value.email(), value.name(), value.role()); }
}
record AuthResult(String token, Person user) {}
record Credentials(@NotBlank @Email @Size(max=254) String email, @NotBlank @Size(max=72) String password) {}
record Registration(@NotBlank @Size(max=70) String name, @NotBlank @Email @Size(max=254) String email,
                    @NotBlank @Size(min=12,max=72) String password, @Size(max=16) String role) {}

interface IdentityStore {
    Optional<Account> byEmail(String email);
    Optional<Account> account(String id);
    void store(Account user);
    void start(LoginSession session);
    boolean active(String id, String user);
    void revoke(String id);
}

@Service
class Auth {
    final SecretKeySpec key;
    final IdentityStore store;
    final String issuer;
    final PasswordEncoder bcrypt = new BCryptPasswordEncoder(11);
    private final String missing = bcrypt.encode(UUID.randomUUID().toString());
    private static final Set<String> SIGNUP_ROLES = Set.of("RIDER", "DRIVER");

    Auth(IdentityStore store, @Value("${app.jwt-secret}") String secret, @Value("${spring.application.name}") String issuer, Environment env) throws Exception {
        this.store = store;
        this.issuer = issuer;
        if (secret.isBlank()) {
            if (env.acceptsProfiles(Profiles.of("render"))) throw new IllegalStateException("JWT_SECRET is required in the render profile");
            byte[] value = new byte[32];
            new SecureRandom().nextBytes(value);
            key = new SecretKeySpec(value, "HmacSHA256");
        } else {
            if (secret.length() < 32) throw new IllegalArgumentException("JWT_SECRET needs at least 32 characters");
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        }
    }

    static String email(String raw) { return raw.strip().toLowerCase(Locale.ROOT); }
    static String id(Jwt jwt) { return jwt.getSubject(); }

    private static void checkPassword(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > 72) throw Problems.bad("Password must fit within 72 UTF-8 bytes.");
    }

    AuthResult register(Registration input) {
        checkPassword(input.password());
        String role = input.role() == null ? "RIDER" : input.role();
        if (!SIGNUP_ROLES.contains(role)) throw Problems.bad("Choose a valid account type.");
        String address = email(input.email());
        if (store.byEmail(address).isPresent()) throw Problems.conflict("This email is already registered.");
        var account = new Account(UUID.randomUUID().toString(), address, input.name().strip(), bcrypt.encode(input.password()), role);
        store.store(account);
        return issue(account);
    }

    AuthResult login(Credentials input) {
        checkPassword(input.password());
        Account account = store.byEmail(email(input.email())).orElse(null);
        if (!bcrypt.matches(input.password(), account == null || account.hash() == null ? missing : account.hash()) || account == null || account.hash() == null) {
            throw Problems.of(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }
        return issue(account);
    }

    AuthResult issue(Account account) {
        Instant now = Instant.now();
        Instant expires = now.plus(Duration.ofHours(8));
        String id = UUID.randomUUID().toString();
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
            .issuer(issuer).subject(account.id()).jwtID(id).issueTime(Date.from(now)).expirationTime(Date.from(expires)).build());
        try { token.sign(new MACSigner(key.getEncoded())); }
        catch (JOSEException error) { throw new IllegalStateException("JWT signing unavailable", error); }
        store.start(new LoginSession(id, account.id(), expires));
        return new AuthResult(token.serialize(), Person.view(account));
    }
}

@RestController
@RequestMapping("/api/auth")
class IdentityController {
    private final Auth auth;
    private final Map<String,Integer> requests = new HashMap<>();
    private long minute;
    private int total;
    IdentityController(Auth auth) { this.auth = auth; }

    private synchronized void throttle(HttpServletRequest request, String email) {
        long now = System.currentTimeMillis() / 60000;
        if (minute != now) { minute = now; requests.clear(); total = 0; }
        if (++total > 180 || requests.merge("ip:" + request.getRemoteAddr(), 1, Integer::sum) > 25
                || requests.merge("email:" + Auth.email(email), 1, Integer::sum) > 10) {
            throw Problems.of(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again in a minute.");
        }
    }

    @PostMapping("/signup") @ResponseStatus(HttpStatus.CREATED)
    AuthResult signup(@Valid @RequestBody Registration value, HttpServletRequest request) {
        throttle(request, value.email()); return auth.register(value);
    }

    @PostMapping("/login") AuthResult login(@Valid @RequestBody Credentials value, HttpServletRequest request) {
        throttle(request, value.email()); return auth.login(value);
    }

    @GetMapping("/me") Person me(@AuthenticationPrincipal Jwt jwt) {
        return auth.store.account(Auth.id(jwt)).map(Person::view).orElseThrow(Problems::missing);
    }

    @PostMapping("/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal Jwt jwt) { auth.store.revoke(jwt.getId()); }
}
