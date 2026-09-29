package com.shortyurl.service;

import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@Service
public class UrlCreateService {

	private static final int SHORT_CODE_ATTEMPTS = 8;

	private final BindingRepository bindingRepository;
	private final UrlSanitizer urlSanitizer;
	private final ShortCodeGenerator shortCodeGenerator;
	private final UrlLivenessChecker urlLivenessChecker;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;
	private final Duration inactivity;

	public UrlCreateService(
			BindingRepository bindingRepository,
			UrlSanitizer urlSanitizer,
			ShortCodeGenerator shortCodeGenerator,
			UrlLivenessChecker urlLivenessChecker,
			PlatformTransactionManager transactionManager,
			Clock clock,
			@Value("${shortyurl.inactivity}") Duration inactivity) {
		this.bindingRepository = bindingRepository;
		this.urlSanitizer = urlSanitizer;
		this.shortCodeGenerator = shortCodeGenerator;
		this.urlLivenessChecker = urlLivenessChecker;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.inactivity = inactivity;
	}

	public CreateUrlResult create(String rawUrl) {
		String originalUrl = rawUrl.trim();
		String sanitizedUrl = urlSanitizer.sanitize(originalUrl);
		urlLivenessChecker.check(originalUrl);

		return bindingRepository.findBySanitizedUrl(sanitizedUrl)
				.map(this::reuseExisting)
				.orElseGet(() -> persistNewBinding(originalUrl, sanitizedUrl));
	}

	private CreateUrlResult reuseExisting(Binding existing) {
		if (!isExpired(existing)) {
			return new CreateUrlResult(existing, false);
		}
		Binding reactivated = transactionTemplate.execute(status -> {
			Binding binding = bindingRepository.findById(existing.getId()).orElseThrow();
			if (!isExpired(binding)) {
				return binding;
			}
			binding.setLastAccessedAt(clock.instant());
			return bindingRepository.saveAndFlush(binding);
		});
		return new CreateUrlResult(reactivated, false);
	}

	private boolean isExpired(Binding binding) {
		return Inactivity.isExpired(
				binding.getCreatedAt(),
				binding.getLastAccessedAt(),
				clock.instant(),
				inactivity);
	}

	private CreateUrlResult persistNewBinding(String originalUrl, String sanitizedUrl) {
		for (int attempt = 0; attempt < SHORT_CODE_ATTEMPTS; attempt++) {
			String shortCode = shortCodeGenerator.generate();
			try {
				Binding saved = transactionTemplate.execute(status -> {
					Binding binding = new Binding();
					binding.setOriginalUrl(originalUrl);
					binding.setSanitizedUrl(sanitizedUrl);
					binding.setShortCode(shortCode);
					binding.setCreatedAt(clock.instant());
					return bindingRepository.saveAndFlush(binding);
				});
				return new CreateUrlResult(saved, true);
			}
			catch (DataIntegrityViolationException ex) {
				Optional<Binding> existing = bindingRepository.findBySanitizedUrl(sanitizedUrl);
				if (existing.isPresent()) {
					return reuseExisting(existing.get());
				}
				// shortCode unique conflict — regenerate and retry
			}
		}
		throw new IllegalStateException("Unable to allocate a unique short code");
	}
}
