package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link JpaCart} entities in {@code carts}.
 * <p>
 * Maps user association through {@link JpaCart#getUser()} ({@code user.id})
 * matching legacy {@code CartRepository} contracts.
 * </p>
 * <p>
 * <b>Cart Cascade & Deletion Semantics:</b>
 * <ul>
 *   <li>Deleting a cart by associated user ID removes the cart record and cascades
 *       to delete all child {@code cart_items} rows (via database FK cascade and JPA orphan removal).</li>
 *   <li>The associated {@link com.lordgainsborough.restaurant.persistence.jpa.entity.JpaUser}
 *       is NEVER deleted or modified during cart deletion.</li>
 *   <li>{@link #findByUserId(String)} uses explicit parameterized JPQL traversal ({@code c.user.id = :userId})
 *       to guarantee safe, unambiguous navigation across the {@code user} entity relationship.</li>
 *   <li>{@link #findByUser_Id(String)} provides native Spring Data nested-property traversal.</li>
 * </ul>
 * </p>
 */
@Repository
public interface JpaCartRepository extends JpaRepository<JpaCart, String> {

    /**
     * Resolves the cart belonging to the user with the given ID via explicit JPQL.
     *
     * @param userId primary key of the associated user
     * @return an {@link Optional} containing the user's cart, or empty if none exists
     */
    @Query("SELECT c FROM JpaCart c WHERE c.user.id = :userId")
    Optional<JpaCart> findByUserId(@Param("userId") String userId);

    /**
     * Resolves the cart belonging to the user with the given ID via nested property traversal.
     *
     * @param userId primary key of the associated user
     * @return an {@link Optional} containing the user's cart, or empty if none exists
     */
    Optional<JpaCart> findByUser_Id(String userId);

    /**
     * Deletes the cart belonging to the user with the given ID via explicit JPQL.
     * <p>
     * Relies on the PostgreSQL foreign key constraint {@code fk_cart_items_cart ON DELETE CASCADE}
     * to safely and completely remove all child cart items without touching the referenced user.
     * </p>
     *
     * @param userId primary key of the associated user
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM JpaCart c WHERE c.user.id = :userId")
    void deleteByUserId(@Param("userId") String userId);

    /**
     * Deletes the cart belonging to the user with the given ID via Spring Data derived delete.
     *
     * @param userId primary key of the associated user
     */
    @Transactional
    void deleteByUser_Id(String userId);
}
