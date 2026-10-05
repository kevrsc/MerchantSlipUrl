package com.merchantslipurl.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VanityTest {

	@Test
	void requireNull_isRejected() {
		assertThatThrownBy(() -> Vanity.require(null))
				.isInstanceOf(InvalidVanityException.class);
	}
}
