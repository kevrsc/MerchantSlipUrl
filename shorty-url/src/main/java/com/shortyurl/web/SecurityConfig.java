package com.shortyurl.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
				.httpBasic(Customizer.withDefaults());
		return http.build();
	}
}
