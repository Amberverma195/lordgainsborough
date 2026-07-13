package com.lordgainsborough.restaurant.repository;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CustomerOrderRepository extends MongoRepository<CustomerOrder, String> {
    List<CustomerOrder> findByPickupDateOrderByCreatedAtDesc(String pickupDate);
    List<CustomerOrder> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);
    List<CustomerOrder> findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(String customerEmail);
    List<CustomerOrder> findByCustomerPhoneOrderByCreatedAtDesc(String customerPhone);
    long countByCustomerEmailIgnoreCase(String customerEmail);
    long countByCustomerPhone(String customerPhone);
    long countByCustomerEmailIgnoreCaseAndStatus(String customerEmail, String status);
    long countByCustomerPhoneAndStatus(String customerPhone, String status);
}
