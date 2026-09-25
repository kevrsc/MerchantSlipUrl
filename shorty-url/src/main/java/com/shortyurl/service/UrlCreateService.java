package com.shortyurl.service;

import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class UrlCreateService {

	private static final int SHORT_CODE_ATTEMPTS = 8;

	private final BindingRepository bindingRepository;
	private final UrlSanitizer urlSanitizer;
	private final ShortCodeGenerator shortCodeGenerator;
	private final TransactionTemplate transactionTemplate;

	public UrlCreateService(
			BindingRepository bindingRepository,
			UrlSanitizer urlSanitizer,
			ShortCodeGenerator shortCodeGenerator,
			PlatformTransactionManager transactionManager) {
		this.bindingRepository = bindingRepository;
		this.urlSanitizer = urlSanitizer;
		this.shortCodeGenerator = shortCodeGenerator;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public CreateUrlResult create(String rawUrl) {
		String originalUrl = rawUrl.trim();
		String sanitizedUrl = urlSanitizer.sanitize(originalUrl);

		return bindingRepository.findBySanitizedUrl(sanitizedUrl)
				.map(existing -> new CreateUrlResult(existing, false))
				.orElseGet(() -> persistNewBinding(originalUrl, sanitizedUrl));
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
					return bindingRepository.saveAndFlush(binding);
				});
				return new CreateUrlResult(saved, true);
			}
			catch (DataIntegrityViolationException ex) {
				Optional<Binding> existing = bindingRepository.findBySanitizedUrl(sanitizedUrl);
				if (existing.isPresent()) {
					return new CreateUrlResult(existing.get(), false);
				}
				// shortCode unique conflict — regenerate and retry
			}
		}
		throw new IllegalStateException("Unable to allocate a unique short code");
	}
}
