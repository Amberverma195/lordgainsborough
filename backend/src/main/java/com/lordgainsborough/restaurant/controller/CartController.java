package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.Cart;
import com.lordgainsborough.restaurant.repository.CartRepository;
import com.lordgainsborough.restaurant.repository.UserRepository;
import com.lordgainsborough.restaurant.security.JwtUtil;
import com.lordgainsborough.restaurant.service.CartStore;
import com.lordgainsborough.restaurant.service.InvalidCartItemException;
import com.lordgainsborough.restaurant.service.MongoCartStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartStore carts;

    @Autowired
    public CartController(CartStore carts) { this.carts = carts; }

    /** Retained for existing direct callers; Spring selects the store-based constructor. */
    public CartController(CartRepository carts, UserRepository users, JwtUtil jwt) {
        this(new MongoCartStore(carts, users, jwt, Clock.systemUTC()));
    }

    public CartStore getCartStore() { return carts; }

    @GetMapping
    public ResponseEntity<?> getCart(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        return cartResponse(carts.read(bearerToken(authHeader)));
    }

    /** PUT still consumes a raw item array and replaces the full saved snapshot. */
    @PutMapping
    public ResponseEntity<?> saveCart(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody List<Cart.CartItem> items) {
        try {
            return cartResponse(carts.replace(bearerToken(authHeader), items));
        } catch (InvalidCartItemException invalid) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "INVALID_CART_ITEM", "message", invalid.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> clearCart(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        return carts.clear(bearerToken(authHeader))
                ? ResponseEntity.ok(Collections.singletonMap("message", "Cart cleared.")) : unauthorized();
    }

    private static ResponseEntity<?> cartResponse(Optional<Cart> cart) {
        if (cart.isEmpty()) return unauthorized();
        return ResponseEntity.ok(Collections.singletonMap("items", cart.get().getItems()));
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing or invalid token.");
    }

    private static String bearerToken(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
