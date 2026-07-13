package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.Cart;
import com.lordgainsborough.restaurant.model.User;
import com.lordgainsborough.restaurant.repository.CartRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/cart")
@CrossOrigin(origins = "*")
public class CartController {

    private final CartRepository cartRepository;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public CartController(CartRepository cartRepository, UserRepository userRepository, JwtUtil jwtUtil) {
        this.cartRepository = cartRepository;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    /**
     * GET /api/cart — Return the authenticated user's saved cart.
     */
    @GetMapping
    public ResponseEntity<?> getCart(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> userOpt = getAuthenticatedUser(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        }

        Optional<Cart> cartOpt = cartRepository.findByUserId(userOpt.get().getId());
        if (cartOpt.isEmpty()) {
            return ResponseEntity.ok(Collections.singletonMap("items", Collections.emptyList()));
        }

        Cart cart = cartOpt.get();
        if (cart.isExpired()) {
            cartRepository.delete(cart);
            return ResponseEntity.ok(Collections.singletonMap("items", Collections.emptyList()));
        }

        return ResponseEntity.ok(Collections.singletonMap("items", cart.getItems()));
    }

    /**
     * PUT /api/cart — Replace the user's entire cart with the provided items.
     */
    @PutMapping
    public ResponseEntity<?> saveCart(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody List<Cart.CartItem> items) {
        Optional<User> userOpt = getAuthenticatedUser(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        }

        String userId = userOpt.get().getId();
        Cart cart = cartRepository.findByUserId(userId).orElseGet(() -> {
            Cart newCart = new Cart();
            newCart.setUserId(userId);
            return newCart;
        });

        cart.setItems(items != null ? items : Collections.emptyList());
        cart.setUpdatedAt(Instant.now());
        cartRepository.save(cart);

        return ResponseEntity.ok(Collections.singletonMap("items", cart.getItems()));
    }

    /**
     * DELETE /api/cart — Clear the user's cart.
     */
    @DeleteMapping
    public ResponseEntity<?> clearCart(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> userOpt = getAuthenticatedUser(authHeader);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
        }

        cartRepository.deleteByUserId(userOpt.get().getId());
        return ResponseEntity.ok(Collections.singletonMap("message", "Cart cleared."));
    }

    // --- Helpers ---

    private Optional<User> getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return Optional.empty();
        }

        String email = jwtUtil.getEmailFromToken(token);
        return userRepository.findByEmail(email);
    }
}
