package dev.edo.sonar.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.edo.sonar.domain.Preferences;

public interface PreferencesRepository extends JpaRepository<Preferences, Long> {

	/** The single preferences row, or defaults when nothing has been saved yet. */
	default Preferences get() {
		return findById(1L).orElseGet(Preferences::new);
	}
}
