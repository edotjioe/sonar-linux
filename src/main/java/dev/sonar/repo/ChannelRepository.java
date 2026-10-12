package dev.sonar.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.sonar.domain.Channel;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

	Optional<Channel> findByName(String name);

	Optional<Channel> findBySinkName(String sinkName);

	List<Channel> findAllByOrderByPositionAsc();
}
