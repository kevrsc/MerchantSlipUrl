package com.merchantslipurl.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlSanitizerTest {

	private final UrlSanitizer sanitizer = new UrlSanitizer();

	@Test
	void lowercasesHostDropsFragmentAndTrims() {
		String sanitized = sanitizer.sanitize("  HTTPS://Shop.Example.COM/a/b?x=1#section  ");
		assertThat(sanitized).isEqualTo("https://shop.example.com/a/b?x=1");
	}

	@Test
	void trailingSlashCollapsesToTheSameKey() {
		assertThat(sanitizer.sanitize("https://shop.example.com/a/b/"))
				.isEqualTo("https://shop.example.com/a/b");
		assertThat(sanitizer.sanitize("https://shop.example.com/a/b//"))
				.isEqualTo("https://shop.example.com/a/b");
		assertThat(sanitizer.sanitize("https://shop.example.com/a/b/?x=1"))
				.isEqualTo("https://shop.example.com/a/b?x=1");
		assertThat(sanitizer.sanitize("https://shop.example.com/"))
				.isEqualTo("https://shop.example.com");
		assertThat(sanitizer.sanitize("https://shop.example.com"))
				.isEqualTo("https://shop.example.com");
	}

	@Test
	void rejectsNonHttpSchemes() {
		assertThatThrownBy(() -> sanitizer.sanitize("ftp://files.example.com/a"))
				.isInstanceOf(InvalidUrlException.class);
	}

	@Test
	void rejectsBlank() {
		assertThatThrownBy(() -> sanitizer.sanitize(" "))
				.isInstanceOf(InvalidUrlException.class);
	}

	@Test
	void rejectsNull() {
		assertThatThrownBy(() -> sanitizer.sanitize(null))
				.isInstanceOf(InvalidUrlException.class)
				.hasMessage("url must not be blank");
	}

	@Test
	void rejectsMissingScheme() {
		assertThatThrownBy(() -> sanitizer.sanitize("shop.example.com/a"))
				.isInstanceOf(InvalidUrlException.class)
				.hasMessage("url must use http or https");
	}

	@Test
	void rejectsHostlessUrl() {
		assertThatThrownBy(() -> sanitizer.sanitize("http:///path"))
				.isInstanceOf(InvalidUrlException.class);
	}

	@Test
	void rejectsMalformedUrl() {
		assertThatThrownBy(() -> sanitizer.sanitize("http://"))
				.isInstanceOf(InvalidUrlException.class);
	}

	@Test
	void rejectsSanitizedUrlLongerThanColumn() {
		String raw = "http://example.com/" + "ä".repeat(400);
		assertThat(raw.length()).isLessThanOrEqualTo(UrlSanitizer.MAX_SANITIZED_LENGTH);
		assertThatThrownBy(() -> sanitizer.sanitize(raw))
				.isInstanceOf(InvalidUrlException.class)
				.hasMessage("url is too long");
	}
}
