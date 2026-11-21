package com.lordgainsborough.restaurant.security;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class JwtSessionRevocationTest {
    @Test void storeFailureIsNotMisreportedAsAnInvalidSession() {
        var store=mock(SessionRevocationStore.class);
        var jwt=new JwtUtil(store);
        ReflectionTestUtils.setField(jwt,"secret","test-revocation-secret-at-least-thirty-two-bytes");
        ReflectionTestUtils.setField(jwt,"expirationTime",60000L);
        String token=jwt.generateToken("user","user@example.com",0);
        when(store.isRevoked(token)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(()->jwt.validateToken(token)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThatThrownBy(()->jwt.validateTokenForIdentity(token,"user",0)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(store,times(2)).isRevoked(token);
        assertThat(jwt.validateToken("invalid")).isFalse();
        verifyNoMoreInteractions(store);
    }
}
