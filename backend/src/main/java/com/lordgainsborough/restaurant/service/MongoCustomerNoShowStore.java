package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CustomerOrderRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Tracks per-customer no-show counts on the User document and gates pickup after the threshold.
 */
@Component("mongoCustomerNoShowStore")
public class MongoCustomerNoShowStore implements CustomerNoShowStore {

    private static final int NO_SHOW_THRESHOLD = CustomerNoShowService.NO_SHOW_THRESHOLD;
    private static final String NO_SHOW_STATUS = CustomerNoShowService.NO_SHOW_STATUS;

    private final UserRepository userRepository;
    private final CustomerOrderRepository orderRepository;

    public MongoCustomerNoShowStore(UserRepository userRepository, CustomerOrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }

    public boolean isPickupDisabled(User user) {
        return user != null && user.getNoShowCount() >= NO_SHOW_THRESHOLD;
    }

    public boolean isPickupDisabledForCustomer(String email, String phone) {
        return findCustomer(email, phone)
                .map(this::isPickupDisabled)
                .orElse(false);
    }

    /**
     * Syncs the matching customer's no-show count from NO_SHOW orders when KDS records a first-time no-show.
     */
    public void recordNoShow(CustomerOrder order) {
        findCustomer(order.getCustomerEmail(), order.getCustomerPhone()).ifPresent(user -> {
            user.setNoShowCount(countNoShowsForCustomer(user));
            userRepository.save(user);
        });
    }

    public int countNoShowsForCustomer(User user) {
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return (int) orderRepository.countByCustomerEmailIgnoreCaseAndStatus(
                    user.getEmail().trim(), NO_SHOW_STATUS);
        }
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            return (int) orderRepository.countByCustomerPhoneAndStatus(
                    user.getPhone().trim(), NO_SHOW_STATUS);
        }
        return 0;
    }

    public Optional<User> findCustomer(String email, String phone) {
        if (email != null && !email.isBlank()) {
            Optional<User> byEmail = userRepository.findByEmailIgnoreCase(email.trim());
            if (byEmail.isPresent()) {
                return byEmail;
            }
        }

        String normalizedPhone = normalizePhone(phone);
        if (!normalizedPhone.isEmpty()) {
            List<User> byPhone = userRepository.findByPhoneNormalized(normalizedPhone);
            if (!byPhone.isEmpty()) {
                return Optional.of(byPhone.get(0));
            }
        }

        return Optional.empty();
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("1") && digits.length() > 10) {
            digits = digits.substring(1);
        }
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }
}
