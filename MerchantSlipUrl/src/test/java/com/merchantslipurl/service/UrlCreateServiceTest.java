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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class UrlCreateServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private static final String ORIGINAL = "https://example.com/orders/42";
	private static final Duration INACTIVITY = Duration.ofMinutes(5);

	private final BindingRepository bindingRepository = mock(BindingRepository.class);
	private final ShortCodeGenerator shortCodeGenerator = mock(ShortCodeGenerator.class);
	private final UrlLivenessChecker urlLivenessChecker = mock(UrlLivenessChecker.class);
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final UrlSanitizer urlSanitizer = new UrlSanitizer();

	private UrlCreateService service;

	@BeforeEach
	void setUp() {
		service = new UrlCreateService(
				bindingRepository,
				urlSanitizer,
				shortCodeGenerator,
				urlLivenessChecker,
				new ImmediateTransactionManager(),
				clock,
				INACTIVITY);
	}

	@Test
	void generatedInsertLostToExistingDestination_returnsThatRow() {
		String sanitized = urlSanitizer.sanitize(ORIGINAL);
		Binding existing = binding(UUID.randomUUID(), "kept", ORIGINAL, sanitized, NOW, null);
		when(bindingRepository.findBySanitizedUrl(sanitized))
				.thenReturn(Optional.empty(), Optional.of(existing));
		when(bindingRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
		when(shortCodeGenerator.generate()).thenReturn("new-code");
		when(bindingRepository.saveAndFlush(any(Binding.class))).thenThrow(new DataIntegrityViolationException("sanitized"));

		CreateUrlResult result = service.create(ORIGINAL, null);

		assertThat(result.created()).isFalse();
		assertThat(result.binding().getShortCode()).isEqualTo("kept");
	}

	@Test
	void generatedCodeCollisions_failAfterEightAttempts() {
		when(bindingRepository.findBySanitizedUrl(anyString())).thenReturn(Optional.empty());
		when(shortCodeGenerator.generate()).thenReturn("collide");
		when(bindingRepository.saveAndFlush(any(Binding.class))).thenThrow(new DataIntegrityViolationException("short code"));

		assertThatThrownBy(() -> service.create(ORIGINAL, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Unable to allocate a unique short code");
	}

	@Test
	void reactivationSeesRowAlreadyRefreshed_returnsCurrentRow() {
		String sanitized = urlSanitizer.sanitize(ORIGINAL);
		UUID id = UUID.randomUUID();
		Binding expired = binding(id, "kept", ORIGINAL, sanitized, NOW.minus(Duration.ofHours(1)), null);
		Binding refreshed = binding(id, "kept", ORIGINAL, sanitized, NOW.minus(Duration.ofHours(1)), NOW);
		when(bindingRepository.findBySanitizedUrl(sanitized)).thenReturn(Optional.of(expired));
		when(bindingRepository.findById(id)).thenReturn(Optional.of(expired), Optional.of(refreshed));

		CreateUrlResult result = service.create(ORIGINAL, "ignored-vanity");

		assertThat(result.created()).isFalse();
		assertThat(result.binding()).isSameAs(refreshed);
	}

	@Test
	void vanityInsertLostToExistingDestination_returnsThatRow() {
		String sanitized = urlSanitizer.sanitize(ORIGINAL);
		Binding existing = binding(UUID.randomUUID(), "kept", ORIGINAL, sanitized, NOW, null);
		when(bindingRepository.findBySanitizedUrl(sanitized))
				.thenReturn(Optional.empty(), Optional.of(existing));
		when(bindingRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
		when(bindingRepository.existsByShortCode("spring-sale")).thenReturn(false);
		when(bindingRepository.saveAndFlush(any(Binding.class))).thenThrow(new DataIntegrityViolationException("sanitized"));

		CreateUrlResult result = service.create(ORIGINAL, "spring-sale");

		assertThat(result.created()).isFalse();
		assertThat(result.binding().getShortCode()).isEqualTo("kept");
	}

	@Test
	void vanityInsertLostToTakenCode_isRefused() {
		when(bindingRepository.findBySanitizedUrl(anyString())).thenReturn(Optional.empty());
		when(bindingRepository.existsByShortCode("spring-sale")).thenReturn(false);
		when(bindingRepository.saveAndFlush(any(Binding.class))).thenThrow(new DataIntegrityViolationException("short code"));

		assertThatThrownBy(() -> service.create(ORIGINAL, "spring-sale"))
				.isInstanceOf(VanityTakenException.class);
	}

	private static Binding binding(
			UUID id,
			String shortCode,
			String originalUrl,
			String sanitizedUrl,
			Instant createdAt,
			Instant lastAccessedAt) {
		Binding binding = new Binding();
		binding.setId(id);
		binding.setShortCode(shortCode);
		binding.setOriginalUrl(originalUrl);
		binding.setSanitizedUrl(sanitizedUrl);
		binding.setCreatedAt(createdAt);
		binding.setLastAccessedAt(lastAccessedAt);
		return binding;
	}
}
