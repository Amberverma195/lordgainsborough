package com.lordgainsborough.restaurant.controller;

import com.lordgainsborough.restaurant.model.*;
import com.lordgainsborough.restaurant.repository.*;
import com.lordgainsborough.restaurant.security.JwtUtil;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Default MongoDB cart compatibility. */
class CartControllerTest {
    @Test void mongoConstructorPreservesReplacementAndNullListHandling() {
        var carts = mock(CartRepository.class); var users = mock(UserRepository.class); var jwt = mock(JwtUtil.class);
        var user = new User(); user.setId("owner"); user.setEmail("owner@example.com");
        when(jwt.validateToken("jwt")).thenReturn(true); when(jwt.getEmailFromToken("jwt")).thenReturn(user.getEmail());
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(jwt.validateTokenForUser("jwt",user)).thenReturn(true);
        var cart = new Cart(); cart.setId("cart"); cart.setUserId("owner");
        when(carts.findByUserId("owner")).thenReturn(Optional.of(cart));
        var controller = new CartController(carts,users,jwt);
        var item = new Cart.CartItem(); item.setName("Snapshot"); item.setPrice(12.99); item.setQuantity(2);
        assertThat(controller.saveCart("Bearer jwt",List.of(item)).getBody()).isEqualTo(Map.of("items",List.of(item)));
        verify(carts).save(cart);
        assertThat(controller.saveCart("Bearer jwt",null).getBody()).isEqualTo(Map.of("items",List.of()));
        assertThat(controller.getCart("Bearer jwt").getBody()).isEqualTo(Map.of("items",List.of()));
        assertThat(controller.clearCart("Bearer jwt").getBody()).isEqualTo(Map.of("message","Cart cleared."));
        verify(carts).deleteByUserId("owner");
    }
    @Test void missingTokenReturnsOriginal401WithoutDatabaseAccess() {
        var carts = mock(CartRepository.class); var users = mock(UserRepository.class); var jwt = mock(JwtUtil.class);
        var controller = new CartController(carts,users,jwt);
        for (var result : List.of(controller.getCart(null),controller.saveCart(null,List.of()),controller.clearCart(null))) {
            assertThat(result.getStatusCode().value()).isEqualTo(401);
            assertThat(result.getBody()).isEqualTo("Missing or invalid token.");
        }
        verifyNoInteractions(carts,users);
    }
}
