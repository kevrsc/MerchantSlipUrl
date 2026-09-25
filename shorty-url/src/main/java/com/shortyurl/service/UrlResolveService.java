package com.shortyurl.service;

import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class UrlResolveService {

	private final BindingRepository bindingRepository;

	public UrlResolveService(BindingRepository bindingRepository) {
		this.bindingRepository = bindingRepository;
	}

	@Transactional
	public String resolve(String shortCode) {
		Binding binding = bindingRepository.findByShortCode(shortCode)
				.orElseThrow(() -> new UrlNotFoundException(shortCode));
		binding.setLastAccessedAt(Instant.now());
		return binding.getOriginalUrl();
	}
}
