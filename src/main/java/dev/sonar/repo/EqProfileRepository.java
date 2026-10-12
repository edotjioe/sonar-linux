package dev.sonar.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sonar.domain.EqProfile;

public interface EqProfileRepository extends JpaRepository<EqProfile, Long> {

	Optional<EqProfile> findByName(String name);
}
