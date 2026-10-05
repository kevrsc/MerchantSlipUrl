package com.shortyurl.persistence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BindingTest {

	@Test
	void onCreateKeepsAssignedIdAndStampsCreatedAt() {
		Binding binding = new Binding();
		UUID id = UUID.randomUUID();
		binding.setId(id);

		binding.onCreate();

		assertThat(binding.getId()).isEqualTo(id);
		assertThat(binding.getCreatedAt()).isNotNull();
	}
}
