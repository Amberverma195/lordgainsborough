package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.Cart;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CartRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Default adapter preserves the original Mongo cart snapshot and strict TTL semantics. */
@Component("mongoCartStore")
public class MongoCartStore implements CartStore {
    private final CartRepository carts;
    private final UserRepository users;
    private final JwtUtil jwt;
    private final Clock clock;

    public MongoCartStore(CartRepository carts, UserRepository users, JwtUtil jwt, Clock clock) {
        this.carts = carts;
        this.users = users;
        this.jwt = jwt;
        this.clock = clock;
    }

    private Optional<User> authenticated(String token) {
        return CartTokenSupport.email(jwt, token).flatMap(users::findByEmailIgnoreCase)
                .filter(user -> jwt.validateTokenForUser(token, user));
    }

    @Override public Optional<Cart> read(String token) {
        return authenticated(token).map(user -> carts.findByUserId(user.getId()).map(cart -> {
            if (cart.getUpdatedAt() == null
                    || cart.getUpdatedAt().isBefore(clock.instant().minusSeconds(Cart.TTL_SECONDS))) {
                carts.delete(cart);
                return new Cart();
            }
            return cart;
        }).orElseGet(Cart::new));
    }

    @Override public Optional<Cart> replace(String token, List<Cart.CartItem> items) {
        return authenticated(token).map(user -> {
            Cart cart = carts.findByUserId(user.getId()).orElseGet(Cart::new);
            cart.setUserId(user.getId());
            cart.setItems(items != null ? items : Collections.emptyList());
            cart.setUpdatedAt(clock.instant());
            carts.save(cart);
            return cart;
        });
    }

    @Override public boolean clear(String token) {
        return authenticated(token).map(user -> {
            carts.deleteByUserId(user.getId());
            return true;
        }).orElse(false);
    }
}
