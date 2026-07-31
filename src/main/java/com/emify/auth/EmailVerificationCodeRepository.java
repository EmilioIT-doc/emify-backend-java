package com.emify.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findByEmail(String email);

    Optional<EmailVerificationCode> findByEmailAndCode(String email, String code);

    @Modifying
    @Transactional
    @Query("DELETE FROM EmailVerificationCode e WHERE e.email = :email")
    void deleteByEmail(String email);
}