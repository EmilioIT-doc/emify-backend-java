package com.emify.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {
    List<SubscriptionPayment> findByBusinessTypeAndBusinessIdOrderByPaidAtDesc(String businessType, Long businessId);
}
