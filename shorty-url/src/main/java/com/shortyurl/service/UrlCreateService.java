package com.shortyurl.service;

import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(UrlCreateService.class);

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

	public CreateUrlResult create(String rawUrl, String vanity) {
		String originalUrl = rawUrl.trim();
		String sanitizedUrl;
		String requestedVanity;
		try {
			sanitizedUrl = urlSanitizer.sanitize(originalUrl);
			requestedVanity = Vanity.optional(vanity);
		}
		catch (InvalidUrlException | InvalidVanityException ex) {
			log.warn("Create refused: {}", ex.getMessage());
			throw ex;
		}
		Optional<Binding> existing = bindingRepository.findBySanitizedUrl(sanitizedUrl);
		if (existing.isEmpty()
				&& requestedVanity != null
				&& bindingRepository.existsByShortCode(requestedVanity)) {
			log.warn("Create refused for {}: vanity {} is already taken", originalUrl, requestedVanity);
			throw new VanityTakenException(requestedVanity);
		}

		try {
			urlLivenessChecker.check(originalUrl);
		}
		catch (LivenessFailedException ex) {
			log.warn("Create refused for {}: destination failed the liveness check", originalUrl);
			throw ex;
		}

		return existing
				.map(this::reuseExisting)
				.orElseGet(() -> requestedVanity == null
						? persistNewBinding(originalUrl, sanitizedUrl)
						: persistVanity(originalUrl, sanitizedUrl, requestedVanity));
	}

	private CreateUrlResult reuseExisting(Binding existing) {
		Binding current = bindingRepository.findById(existing.getId()).orElseThrow();
		if (!isExpired(current)) {
			log.info("Create returned existing short URL {} for {}", current.getShortCode(), current.getOriginalUrl());
			return new CreateUrlResult(current, false);
		}
		Binding reactivated = transactionTemplate.execute(status -> {
			Binding binding = bindingRepository.findById(existing.getId()).orElseThrow();
			if (!isExpired(binding)) {
				return binding;
			}
			binding.setLastAccessedAt(clock.instant());
			return bindingRepository.saveAndFlush(binding);
		});
		log.info("Create reactivated expired short URL {} for {}", reactivated.getShortCode(), reactivated.getOriginalUrl());
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
				log.info("Created short URL {} for {}", saved.getShortCode(), originalUrl);
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
		log.error("Create failed for {}: unable to allocate a unique short code", originalUrl);
		throw new IllegalStateException("Unable to allocate a unique short code");
	}

	private CreateUrlResult persistVanity(String originalUrl, String sanitizedUrl, String vanity) {
		try {
			Binding saved = transactionTemplate.execute(status -> {
				Binding binding = new Binding();
				binding.setOriginalUrl(originalUrl);
				binding.setSanitizedUrl(sanitizedUrl);
				binding.setShortCode(vanity);
				binding.setCreatedAt(clock.instant());
				return bindingRepository.saveAndFlush(binding);
			});
			log.info("Created short URL {} for {}", saved.getShortCode(), originalUrl);
			return new CreateUrlResult(saved, true);
		}
		catch (DataIntegrityViolationException ex) {
			Optional<Binding> existing = bindingRepository.findBySanitizedUrl(sanitizedUrl);
			if (existing.isPresent()) {
				return reuseExisting(existing.get());
			}
			log.warn("Create refused for {}: vanity {} is already taken", originalUrl, vanity);
			throw new VanityTakenException(vanity);
		}
	}
}
