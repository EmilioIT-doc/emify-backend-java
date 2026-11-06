package com.emify.security.session;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface AuthEventRepository extends JpaRepository<AuthEvent, Long> {

    List<AuthEvent> findTop30ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    @Modifying
    @Transactional
    @Query("delete from AuthEvent e where e.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
