package com.shortyurl.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BindingRepository extends JpaRepository<Binding, UUID> {

	Optional<Binding> findBySanitizedUrl(String sanitizedUrl);

	boolean existsByShortCode(String shortCode);
}
