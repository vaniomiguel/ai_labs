package pt.conta.security;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import pt.conta.shared.AccountingProperties;

/** Session authentication, persistent password hashes and same-origin CSRF protection. */
@Configuration
public class SecurityConfiguration {
  /**
   * Creates the single owner account once; changing an environment variable never silently resets
   * it.
   */
  @Bean
  ApplicationRunner ownerBootstrap(
      JdbcTemplate jdbc, AccountingProperties properties, Environment environment) {
    return args -> {
      boolean development = environment.acceptsProfiles(Profiles.of("dev & !prod"));
      if (properties.adminPassword() == null || properties.adminPassword().isBlank())
        throw new IllegalStateException("ADMIN_PASSWORD não pode estar vazia");
      if (!development && properties.adminPassword().length() < 12)
        throw new IllegalStateException("ADMIN_PASSWORD tem de ter pelo menos 12 caracteres");
      jdbc.update(
          "insert into app_user(username,password_hash) values (?,?) on conflict(username) do"
              + " nothing",
          properties.adminUsername(),
          new BCryptPasswordEncoder().encode(properties.adminPassword()));
    };
  }

  /** Reads only the password hash from PostgreSQL for the supplied owner name. */
  @Bean
  UserDetailsService users(JdbcTemplate jdbc) {
    return username ->
        jdbc
            .query(
                "select username,password_hash from app_user where username=?",
                (rs, n) ->
                    User.withUsername(rs.getString(1))
                        .password("{bcrypt}" + rs.getString(2))
                        .roles("OWNER")
                        .build(),
                username)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Utilizador desconhecido"));
  }

  /** Protects every API and original document while allowing the local Angular shell to load. */
  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/v1/csrf", "/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/**", "/actuator/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .csrf(
            c ->
                c.csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/v1/session")
                    .successHandler(
                        (req, res, auth) -> {
                          res.setContentType("application/json");
                          res.getWriter().write("{\"authenticated\":true}");
                        })
                    .failureHandler((req, res, e) -> res.sendError(401, "Credenciais inválidas")))
        .logout(
            l ->
                l.logoutUrl("/api/v1/logout")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) -> res.sendError(401, "Inicia sessão para continuar")))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src"
                                + " 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src"
                                + " 'self' data: blob:; worker-src 'self' blob:; frame-src 'self'"
                                + " blob:; connect-src 'self'")))
        .build();
  }
}
