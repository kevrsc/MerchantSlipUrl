package com.shortyurl.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class MutableClock extends Clock {

	private final ZoneId zone;

	private Instant instant;

	public MutableClock(Instant instant) {
		this.zone = ZoneOffset.UTC;
		this.instant = instant;
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return Clock.fixed(instant, zone);
	}

	@Override
	public Instant instant() {
		return instant;
	}

	public void setInstant(Instant instant) {
		this.instant = instant;
	}

	public void advance(Duration duration) {
		this.instant = this.instant.plus(duration);
	}
}
