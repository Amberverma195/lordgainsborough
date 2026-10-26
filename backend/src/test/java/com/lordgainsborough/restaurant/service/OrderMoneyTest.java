package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class OrderMoneyTest {
    @ParameterizedTest @ValueSource(doubles={Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-0.01,100000000})
    void rejectsInvalidConversion(double value) {
        assertThatThrownBy(() -> OrderMoney.decimal(value)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void roundsFractionalCentsAndSupportsNumericLimit() {
        assertThat(OrderMoney.decimal(1.005)).isEqualByComparingTo("1.01");
        assertThat(OrderMoney.decimal(0.1)).isEqualTo(new BigDecimal("0.10"));
        assertThat(OrderMoney.decimal(99999999.99)).isEqualByComparingTo("99999999.99");
        assertThatThrownBy(() -> OrderMoney.money(null)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(doubles={Double.NaN,Double.POSITIVE_INFINITY,-0.01,1.01})
    void rejectsInvalidTax(double rate) {
        assertThatThrownBy(() -> OrderMoney.calculate(new CustomerOrder(),rate)).isInstanceOf(IllegalArgumentException.class);
    }
}
