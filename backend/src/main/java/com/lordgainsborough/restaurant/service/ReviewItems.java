package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.*;
import java.util.*;
/** Shared legacy normalization, including text-only and non-purchased menu references. */
final class ReviewItems {
    private ReviewItems() {}
    static List<OrderReview.ReviewItem> normalize(OrderReview request, CustomerOrder order) {
        var result = new ArrayList<OrderReview.ReviewItem>();
        if (request == null || request.getItems() == null) return result;
        for (var item : request.getItems()) {
            if (item == null || item.getMenuItemId() == null || item.getMenuItemId().isBlank()) continue;
            String text = item.getDescription() == null ? "" : item.getDescription().trim();
            if (item.getRating() <= 0 && text.isEmpty()) continue;
            var normalized = new OrderReview.ReviewItem();
            normalized.setMenuItemId(item.getMenuItemId());
            String name = item.getItemName() == null ? "Item" : item.getItemName();
            if (order.getItems() != null) for (var purchased : order.getItems()) {
                if (purchased != null && item.getMenuItemId().equals(purchased.getMenuItemId()) && purchased.getName() != null) {
                    name = purchased.getName(); break;
                }
            }
            normalized.setItemName(name); normalized.setRating(Math.max(0, Math.min(5, item.getRating())));
            normalized.setDescription(text); result.add(normalized);
        }
        return result;
    }
    static OrderReview serverReview(CustomerOrder order, List<OrderReview.ReviewItem> items, java.time.LocalDateTime now) {
        var result = new OrderReview(); result.setOrderId(order.getId()); result.setCustomerName(order.getCustomerName());
        result.setCustomerEmail(order.getCustomerEmail()); result.setCustomerPhone(order.getCustomerPhone());
        result.setStatus("PENDING"); result.setItems(items); result.setCreatedAt(now); result.setUpdatedAt(now); return result;
    }
}
