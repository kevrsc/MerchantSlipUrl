package com.merchantslipurl.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
		name = "bindings",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_bindings_short_code", columnNames = "short_code"),
				@UniqueConstraint(name = "uk_bindings_sanitized_url", columnNames = "sanitized_url")
		}
)
public class Binding {

	@Id
	private UUID id;

	@Column(name = "short_code", nullable = false, length = 64)
	private String shortCode;

	@Column(name = "original_url", nullable = false, length = 2048)
	private String originalUrl;

	@Column(name = "sanitized_url", nullable = false, length = 2048)
	private String sanitizedUrl;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_accessed_at")
	private Instant lastAccessedAt;

	@PrePersist
	void onCreate() {
		if (id == null) {
			id = UUID.randomUUID();
		}
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getShortCode() {
		return shortCode;
	}

	public void setShortCode(String shortCode) {
		this.shortCode = shortCode;
	}

	public String getOriginalUrl() {
		return originalUrl;
	}

	public void setOriginalUrl(String originalUrl) {
		this.originalUrl = originalUrl;
	}

	public String getSanitizedUrl() {
		return sanitizedUrl;
	}

	public void setSanitizedUrl(String sanitizedUrl) {
		this.sanitizedUrl = sanitizedUrl;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getLastAccessedAt() {
		return lastAccessedAt;
	}

	public void setLastAccessedAt(Instant lastAccessedAt) {
		this.lastAccessedAt = lastAccessedAt;
	}
}
