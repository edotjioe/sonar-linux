package dev.sonar.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sonar.domain.Preferences;

public interface PreferencesRepository extends JpaRepository<Preferences, Long> {

	/** The single preferences row, or defaults when nothing has been saved yet. */
	default Preferences get() {
		return findById(1L).orElseGet(Preferences::new);
	}
}
