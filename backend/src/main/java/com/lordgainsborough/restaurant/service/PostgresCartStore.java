package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.Cart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCart;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCartRepository;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaUserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class PostgresCartStore implements CartStore {
    private final JpaCartRepository carts;
    private final JpaUserRepository users;
    private final JwtUtil jwt;
    private final Clock clock;
    private final CartMapper mapper = new CartMapper();
    private final TransactionTemplate transactions;
    private final JpaTransactionManager manager;

    public PostgresCartStore(JpaCartRepository carts, JpaUserRepository users, JwtUtil jwt,
            Clock clock, JpaTransactionManager manager) {
        this.manager = manager;
        this.carts = carts;
        this.users = users;
        this.jwt = jwt;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public jakarta.persistence.EntityManagerFactory getEntityManagerFactory() { return manager.getEntityManagerFactory(); }
    public JpaUserRepository getUserRepository() { return users; }

    private <T> Optional<T> withAuthenticatedUser(String token, Function<JpaUser, T> operation) {
        Optional<String> email = CartTokenSupport.email(jwt, token);
        if (email.isEmpty()) return Optional.empty();
        return transactions.execute(status -> users.findForCartByEmailIgnoreCase(email.get())
                // Signature/expiry and revocation are checked again AFTER any wait for the row lock.
                .filter(user -> jwt.validateTokenForIdentity(token, user.getId(), user.getTokenVersion()))
                .map(operation));
    }

    @Override public Optional<Cart> read(String token) {
        return withAuthenticatedUser(token, user -> carts.findByUserId(user.getId()).map(cart -> {
            if (cart.isExpired(clock.instant())) {
                // The same user lock excludes PUT, so cleanup cannot delete a just-refreshed cart.
                carts.delete(cart);
                carts.flush();
                return new Cart();
            }
            return mapper.fromJpa(cart);
        }).orElseGet(Cart::new));
    }

    @Override public Optional<Cart> replace(String token, List<Cart.CartItem> items) {
        return withAuthenticatedUser(token, user -> {
            // Validate and copy the complete snapshot before modifying a managed collection.
            var replacement = mapper.toJpa(items);
            JpaCart cart = carts.findByUserId(user.getId()).orElseGet(() -> new JpaCart(user));
            cart.setItems(replacement); // Assigns parent links; orphan removal deletes prior items.
            cart.touch(clock.instant()); // Deliberately after helper methods that touch system time.
            return mapper.fromJpa(carts.saveAndFlush(cart));
        });
    }

    @Override public boolean clear(String token) {
        return withAuthenticatedUser(token, user -> {
            carts.findByUserId(user.getId()).ifPresent(cart -> {
                // Entity removal preserves persistence-context consistency and never cascades to user.
                carts.delete(cart);
                carts.flush();
            });
            return true;
        }).orElse(false);
    }
}
