package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;

/** Explicit domain boundary: never return managed entities to Jackson or Socket.IO. */
public final class CustomerOrderMapper {
    public JpaCustomerOrder toJpa(CustomerOrder source) {
        JpaCustomerOrder target = new JpaCustomerOrder();
        target.setId(source.getId());
        target.setCustomerName(source.getCustomerName());
        target.setCustomerEmail(source.getCustomerEmail());
        target.setCustomerPhone(source.getCustomerPhone());
        target.setPickupDate(source.getPickupDate());
        target.setPickupTime(source.getPickupTime());
        target.setSpecialInstructions(source.getSpecialInstructions());
        target.setStatus(source.getStatus());
        target.setAverageReadyMinutes(source.getAverageReadyMinutes());
        target.setCreatedAt(source.getCreatedAt());
        target.setAcceptedAt(source.getAcceptedAt());
        target.setReadyAt(source.getReadyAt());
        target.setPickedUpAt(source.getPickedUpAt());
        target.setNoShowAt(source.getNoShowAt());
        target.setTrackingTokenHash(source.getTrackingTokenHash());
        target.setIdempotencyKey(source.getIdempotencyKey());
        target.setReviewed(source.isReviewed());
        target.setSubtotal(OrderMoney.decimal(source.getSubtotal()));
        target.setTax(OrderMoney.decimal(source.getTax()));
        target.setTotal(OrderMoney.decimal(source.getTotal()));
        for (CustomerOrder.OrderItem item : source.getItems()) {
            JpaOrderItem child = new JpaOrderItem(item.getMenuItemId(), item.getName(), item.getQuantity(),
                    OrderMoney.decimal(item.getUnitPrice()), OrderMoney.decimal(item.getTotal()));
            child.setNotes(item.getNotes());
            target.addItem(child);
        }
        if (source.getMessages() != null) {
            for (CustomerOrder.OrderMessage message : source.getMessages()) target.addMessage(toJpa(message));
        }
        return target;
    }

    public JpaOrderMessage toJpa(CustomerOrder.OrderMessage source) {
        JpaOrderMessage target = new JpaOrderMessage(source.getId(), source.getSenderRole(), source.getSenderName(), source.getText());
        target.setCreatedAt(source.getCreatedAt());
        return target;
    }

    public CustomerOrder fromJpa(JpaCustomerOrder source) {
        CustomerOrder target = new CustomerOrder();
        target.setId(source.getId());
        target.setCustomerName(source.getCustomerName());
        target.setCustomerEmail(source.getCustomerEmail());
        target.setCustomerPhone(source.getCustomerPhone());
        target.setPickupDate(source.getPickupDate());
        target.setPickupTime(source.getPickupTime());
        target.setSpecialInstructions(source.getSpecialInstructions());
        target.setStatus(source.getStatus());
        target.setAverageReadyMinutes(source.getAverageReadyMinutes());
        target.setCreatedAt(source.getCreatedAt());
        target.setAcceptedAt(source.getAcceptedAt());
        target.setReadyAt(source.getReadyAt());
        target.setPickedUpAt(source.getPickedUpAt());
        target.setNoShowAt(source.getNoShowAt());
        target.setTrackingTokenHash(source.getTrackingTokenHash());
        target.setIdempotencyKey(source.getIdempotencyKey());
        target.setReviewed(source.isReviewed());
        target.setSubtotal(source.getSubtotal().doubleValue());
        target.setTax(source.getTax().doubleValue());
        target.setTotal(source.getTotal().doubleValue());
        for (JpaOrderItem item : source.getItems()) {
            CustomerOrder.OrderItem child = new CustomerOrder.OrderItem();
            child.setMenuItemId(item.getMenuItemId());
            child.setName(item.getName());
            child.setQuantity(item.getQuantity());
            child.setUnitPrice(item.getUnitPrice().doubleValue());
            child.setTotal(item.getTotal().doubleValue());
            child.setNotes(item.getNotes());
            target.getItems().add(child);
        }
        for (JpaOrderMessage message : source.getMessages()) {
            CustomerOrder.OrderMessage child = new CustomerOrder.OrderMessage();
            child.setId(message.getId());
            child.setSenderRole(message.getSenderRole());
            child.setSenderName(message.getSenderName());
            child.setText(message.getText());
            child.setCreatedAt(message.getCreatedAt());
            target.getMessages().add(child);
        }
        return target;
    }
}
