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
public class UrlAssignService {

	private final BindingRepository bindingRepository;
	private final UrlSanitizer urlSanitizer;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;
	private final Duration inactivity;
	private final String publicBaseUrl;

	public UrlAssignService(
			BindingRepository bindingRepository,
			UrlSanitizer urlSanitizer,
			PlatformTransactionManager transactionManager,
			Clock clock,
			@Value("${shortyurl.inactivity}") Duration inactivity,
			@Value("${shortyurl.public-base-url}") String publicBaseUrl) {
		this.bindingRepository = bindingRepository;
		this.urlSanitizer = urlSanitizer;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.inactivity = inactivity;
		this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
	}

	public Binding assign(String rawUrl, String rawVanity) {
		String vanity = Vanity.require(rawVanity);
		String url = rawUrl.trim();
		Binding current = locate(url).orElseThrow(UrlNotFoundException::missingTarget);
		if (current.getShortCode().equals(vanity)) {
			return current;
		}
		try {
			return transactionTemplate.execute(status -> {
				Binding binding = bindingRepository.findById(current.getId())
						.orElseThrow(UrlNotFoundException::missingTarget);
				if (binding.getShortCode().equals(vanity)) {
					return binding;
				}
				if (bindingRepository.existsByShortCode(vanity)) {
					throw new VanityTakenException(vanity);
				}
				boolean expired = Inactivity.isExpired(
						binding.getCreatedAt(),
						binding.getLastAccessedAt(),
						clock.instant(),
						inactivity);
				binding.setShortCode(vanity);
				if (expired) {
					binding.setLastAccessedAt(clock.instant());
				}
				return bindingRepository.saveAndFlush(binding);
			});
		}
		catch (DataIntegrityViolationException ex) {
			throw new VanityTakenException(vanity);
		}
	}

	private Optional<Binding> locate(String url) {
		if (url.startsWith(publicBaseUrl)) {
			String remainder = url.substring(publicBaseUrl.length());
			if (Vanity.SHORT_CODE.matcher(remainder).matches()) {
				return bindingRepository.findByShortCode(remainder);
			}
		}
		return bindingRepository.findBySanitizedUrl(urlSanitizer.sanitize(url));
	}
}
