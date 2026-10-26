package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.Cart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCartItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Snapshot mapping only. Cart prices are never authoritative order/checkout prices. */
public final class CartMapper {
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    public List<JpaCartItem> toJpa(List<Cart.CartItem> items) {
        List<JpaCartItem> result = new ArrayList<>();
        if (items == null) return result;
        for (Cart.CartItem item : items) {
            if (item == null || item.getName() == null || item.getName().isBlank()
                    || invalidText(item.getName(), 255) || invalidText(item.getMenuItemId(), 64)
                    || invalidText(item.getCategory(), 255) || invalidText(item.getImage(), 1024)
                    || invalidText(item.getDescription(), Integer.MAX_VALUE)
                    || item.getQuantity() < 1 || !Double.isFinite(item.getPrice())) {
                throw new InvalidCartItemException();
            }
            BigDecimal price = BigDecimal.valueOf(item.getPrice());
            if (price.signum() < 0 || price.compareTo(MAX_PRICE) > 0) throw new InvalidCartItemException();
            try {
                // Reject lossy rounding of client snapshots; ordinary two-decimal prices round-trip unchanged.
                price = price.setScale(2, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException excessivePrecision) { throw new InvalidCartItemException(); }
            result.add(new JpaCartItem(item.getMenuItemId(), item.getName(), item.getDescription(),
                    price, item.getCategory(), item.getImage(), item.getQuantity()));
        }
        return result;
    }

    private static boolean invalidText(String value, int maximum) {
        if (value == null) return false;
        if (value.codePointCount(0, value.length()) > maximum) return true;
        // PostgreSQL text cannot represent NUL; reject malformed UTF-16 rather than silently replacing it.
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\0') return true;
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return true;
            } else if (Character.isLowSurrogate(current)) return true;
        }
        return false;
    }

    public Cart fromJpa(JpaCart source) {
        Cart target = new Cart();
        target.setId(source.getId());
        target.setUserId(source.getUserId());
        target.setUpdatedAt(source.getUpdatedAt());
        for (JpaCartItem item : source.getItems()) {
            Cart.CartItem child = new Cart.CartItem();
            child.setMenuItemId(item.getMenuItemId());
            child.setName(item.getName());
            child.setDescription(item.getDescription());
            child.setPrice(item.getPrice().doubleValue());
            child.setCategory(item.getCategory());
            child.setImage(item.getImage());
            child.setQuantity(item.getQuantity());
            target.getItems().add(child);
        }
        return target;
    }
}
