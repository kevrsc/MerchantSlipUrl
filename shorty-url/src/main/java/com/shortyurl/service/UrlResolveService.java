package com.shortyurl.service;

import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class UrlResolveService {

	private final BindingRepository bindingRepository;
	private final Clock clock;
	private final Duration inactivity;

	public UrlResolveService(
			BindingRepository bindingRepository,
			Clock clock,
			@Value("${shortyurl.inactivity}") Duration inactivity) {
		this.bindingRepository = bindingRepository;
		this.clock = clock;
		this.inactivity = inactivity;
	}

	@Transactional
	public String resolve(String shortCode) {
		Binding binding = bindingRepository.findByShortCode(shortCode)
				.orElseThrow(() -> new UrlNotFoundException(shortCode));
		Instant now = clock.instant();
		if (Inactivity.isExpired(binding.getCreatedAt(), binding.getLastAccessedAt(), now, inactivity)) {
			throw new UrlExpiredException(shortCode);
		}
		binding.setLastAccessedAt(now);
		return binding.getOriginalUrl();
	}
}
