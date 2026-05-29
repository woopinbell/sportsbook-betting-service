package com.sportsbook.betting.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA repository for {@link OutboxEvent}. The single non-trivial query, {@link
 * #findUnpublished(Pageable)}, drives the publisher loop — limited by the supplied {@link Pageable}
 * so each tick processes a bounded batch instead of stalling on a backlog.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  @Query("select e from OutboxEvent e where e.publishedAt is null order by e.createdAt asc")
  List<OutboxEvent> findUnpublished(Pageable pageable);
}
