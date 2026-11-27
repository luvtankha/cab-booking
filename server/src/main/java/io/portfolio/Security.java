package io.portfolio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
class Security {
    @Bean JwtDecoder jwtDecoder(Auth auth) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(auth.key).build();
        OAuth2TokenValidator<Jwt> active = jwt -> jwt.getId() != null && jwt.getSubject() != null && auth.store.active(jwt.getId(), jwt.getSubject())
            ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Session expired or revoked.", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(auth.issuer), active));
        return decoder;
    }

    @Bean CorsConfigurationSource corsSource(@Value("${app.origin}") String origin) {
        var source = new UrlBasedCorsConfigurationSource();
        if (!origin.isBlank()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of(origin));
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            config.setExposedHeaders(List.of("Content-Disposition"));
            config.setMaxAge(600L);
            source.registerCorsConfiguration("/api/**", config);
        }
        return source;
    }

    @Bean SecurityFilterChain filter(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> providers, GoogleIdentity google) throws Exception {
        http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(r -> r
                .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/google/exchange", "/api/payments/webhook").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/config").permitAll()
                .requestMatchers("/api/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/actuator/health", "/ws", "/oauth2/**", "/login/oauth2/**").permitAll()
                .requestMatchers("/error").permitAll().anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        if (providers.getIfAvailable() != null) http.oauth2Login(login -> login
            .successHandler(google::success).failureHandler((request, response, failure) -> response.sendRedirect("/?oauth_error=sign-in")));
        return http.build();
    }
}
