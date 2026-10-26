package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.Cart;
import java.util.List;
import java.util.Optional;

/**
 * User-scoped cart persistence. Each operation authenticates the token against its own
 * identity backend; callers cannot supply an unrelated user ID. Empty Optional means
 * unauthorized, while an authenticated user with no cart receives an empty Cart.
 */
public interface CartStore {
    Optional<Cart> read(String token);
    Optional<Cart> replace(String token, List<Cart.CartItem> items);
    boolean clear(String token);
}
