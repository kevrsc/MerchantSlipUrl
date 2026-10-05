package com.merchantslipurl.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		AuthenticationEntryPoint entryPoint = (request, response, authException) -> {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			if (request.getRequestURI().startsWith("/api/")) {
				// Omit WWW-Authenticate so the page can show a sign-in error.
				// Basic's challenge header makes the browser open its login dialog and hold fetch() open.
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.getWriter().write(
						"{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Authentication required\"}");
				return;
			}
			response.setHeader("WWW-Authenticate", "Basic realm=\"Realm\", charset=\"UTF-8\"");
		};
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						// CAP-6: create requires Basic Auth
						.requestMatchers(HttpMethod.POST, "/api/v1/urls").authenticated()
						.requestMatchers(HttpMethod.POST, "/api/v1/urls/**").authenticated()
						// CAP-2 resolve (later): permitAll for public short-code path
						.anyRequest().permitAll()
				)
				.httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
				.exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint));
		return http.build();
	}
}
