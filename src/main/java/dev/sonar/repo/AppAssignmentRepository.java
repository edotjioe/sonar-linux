package dev.sonar.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sonar.domain.AppAssignment;
import dev.sonar.domain.Channel;

public interface AppAssignmentRepository extends JpaRepository<AppAssignment, Long> {

	Optional<AppAssignment> findByBinaryName(String binaryName);

	List<AppAssignment> findAllByChannel(Channel channel);
}
