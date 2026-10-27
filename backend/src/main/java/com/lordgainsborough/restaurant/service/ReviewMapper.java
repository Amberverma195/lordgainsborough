package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.OrderReview;
import com.lordgainsborough.restaurant.persistence.jpa.entity.*;
public class ReviewMapper {
    public JpaOrderReview toJpa(OrderReview source, JpaCustomerOrder order) {
        var target=new JpaOrderReview(order,source.getCustomerName(),source.getCustomerEmail(),source.getCustomerPhone());
        target.setCreatedAt(source.getCreatedAt()); target.setUpdatedAt(source.getUpdatedAt());
        for (var item:source.getItems()) {
            PostgresInput.text(item.getMenuItemId(),64,true); PostgresInput.text(item.getItemName(),255,false);
            PostgresInput.text(item.getDescription(),Integer.MAX_VALUE,false);
            target.addItem(new JpaOrderReviewItem(item.getMenuItemId(),item.getItemName(),item.getRating(),item.getDescription()));
        }
        return target;
    }
    public OrderReview fromJpa(JpaOrderReview source) {
        var result=new OrderReview(); result.setId(source.getId()); result.setOrderId(source.getOrderId());
        result.setCustomerName(source.getCustomerName()); result.setCustomerEmail(source.getCustomerEmail()); result.setCustomerPhone(source.getCustomerPhone());
        result.setStatus(source.getStatus()); result.setCreatedAt(source.getCreatedAt()); result.setUpdatedAt(source.getUpdatedAt());
        result.setItems(source.getItems().stream().map(item -> {
            var dto=new OrderReview.ReviewItem(); dto.setMenuItemId(item.getMenuItemId()); dto.setItemName(item.getItemName());
            dto.setRating(item.getRating()); dto.setDescription(item.getDescription()); return dto;
        }).toList()); return result;
    }
}
