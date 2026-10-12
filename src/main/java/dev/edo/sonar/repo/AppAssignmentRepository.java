package dev.edo.sonar.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.edo.sonar.domain.AppAssignment;
import dev.edo.sonar.domain.Channel;

public interface AppAssignmentRepository extends JpaRepository<AppAssignment, Long> {

	Optional<AppAssignment> findByBinaryName(String binaryName);

	List<AppAssignment> findAllByChannel(Channel channel);
}
