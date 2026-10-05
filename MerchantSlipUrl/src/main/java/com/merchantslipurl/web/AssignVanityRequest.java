package com.merchantslipurl.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignVanityRequest(
		@NotBlank(message = "url must not be blank")
		@Size(max = 2048, message = "url must not exceed 2048 characters")
		String url,
		@NotBlank(message = "vanity must not be blank")
		String vanity
) {
}
