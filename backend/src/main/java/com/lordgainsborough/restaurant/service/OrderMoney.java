package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Decimal boundary for NUMERIC(10,2). HALF_UP matches legacy rounding for nonnegative values. */
public final class OrderMoney {
    private static final BigDecimal MAX = new BigDecimal("99999999.99");
    private OrderMoney() {}

    public static BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid order amount");
        return money(BigDecimal.valueOf(value));
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX) > 0)
            throw new IllegalArgumentException("Invalid order amount");
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static void calculate(CustomerOrder order, double taxRate) {
        if (!Double.isFinite(taxRate) || taxRate < 0 || taxRate > 1)
            throw new IllegalArgumentException("Invalid order tax rate");
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CustomerOrder.OrderItem item : order.getItems()) {
            BigDecimal price = decimal(item.getUnitPrice());
            item.setQuantity(Math.max(1, item.getQuantity()));
            BigDecimal line = money(price.multiply(BigDecimal.valueOf(item.getQuantity())));
            item.setUnitPrice(price.doubleValue());
            item.setTotal(line.doubleValue());
            subtotal = subtotal.add(line);
        }
        subtotal = money(subtotal);
        BigDecimal tax = money(subtotal.multiply(BigDecimal.valueOf(taxRate)));
        order.setSubtotal(subtotal.doubleValue());
        order.setTax(tax.doubleValue());
        order.setTotal(money(subtotal.add(tax)).doubleValue());
    }
}
