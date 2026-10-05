package com.merchantslipurl.service;

import com.merchantslipurl.persistence.Binding;
import com.merchantslipurl.persistence.BindingRepository;
import com.merchantslipurl.support.ImmediateTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UrlAssignServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private static final String ORIGINAL = "https://example.com/orders/42";

	private final BindingRepository bindingRepository = mock(BindingRepository.class);
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	private UrlAssignService service;

	@BeforeEach
	void setUp() {
		service = new UrlAssignService(
				bindingRepository,
				new UrlSanitizer(),
				new ImmediateTransactionManager(),
				clock,
				Duration.ofMinutes(5),
				"http://localhost:8080");
	}

	@Test
	void reloadedRowAlreadyHasVanity_returnsItWithoutWriting() {
		UUID id = UUID.randomUUID();
		Binding located = binding(id, "old-code");
		Binding renamed = binding(id, "spring-sale");
		when(bindingRepository.findBySanitizedUrl(ORIGINAL)).thenReturn(Optional.of(located));
		when(bindingRepository.findById(id)).thenReturn(Optional.of(renamed));

		Binding result = service.assign(ORIGINAL, "spring-sale");

		assertThat(result.getShortCode()).isEqualTo("spring-sale");
		verify(bindingRepository, never()).saveAndFlush(any());
	}

	@Test
	void uniqueConstraintLoss_isRefused() {
		UUID id = UUID.randomUUID();
		Binding located = binding(id, "old-code");
		when(bindingRepository.findBySanitizedUrl(ORIGINAL)).thenReturn(Optional.of(located));
		when(bindingRepository.findById(id)).thenReturn(Optional.of(located));
		when(bindingRepository.existsByShortCode("spring-sale")).thenReturn(false);
		when(bindingRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("short code"));

		assertThatThrownBy(() -> service.assign(ORIGINAL, "spring-sale"))
				.isInstanceOf(VanityTakenException.class);
	}

	private static Binding binding(UUID id, String shortCode) {
		Binding binding = new Binding();
		binding.setId(id);
		binding.setShortCode(shortCode);
		binding.setOriginalUrl(ORIGINAL);
		binding.setSanitizedUrl(ORIGINAL);
		binding.setCreatedAt(NOW);
		return binding;
	}
}
