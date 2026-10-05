package com.merchantslipurl.service;

import java.time.Duration;
import java.time.Instant;

final class Inactivity {

	private Inactivity() {
	}

	static boolean isExpired(Instant createdAt, Instant lastAccessedAt, Instant now, Duration period) {
		Instant anchor = lastAccessedAt != null ? lastAccessedAt : createdAt;
		return !now.isBefore(anchor.plus(period));
	}
}
