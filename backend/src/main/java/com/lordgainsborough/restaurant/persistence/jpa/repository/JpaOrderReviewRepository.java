package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaOrderReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link JpaOrderReview} entities in {@code order_reviews}.
 * <p>
 * Maps order association through {@link JpaOrderReview#getOrder()} ({@code order.id})
 * matching legacy {@code OrderReviewRepository} contracts.
 * </p>
 * <p>
 * <b>Review & Order Integrity Semantics:</b>
 * <ul>
 *   <li>The unique constraint {@code uq_order_reviews_order_id} ensures at most one review per order.
 *       The legacy list-returning method {@link #findByOrderIdOrderByCreatedAtDesc(String)} returns
 *       a List containing at most one item, ordered by {@code createdAt DESC}.</li>
 *   <li>{@link #existsByOrderId(String)} checks existence efficiently without loading entities.</li>
 *   <li>Deleting a review cascades to delete child {@code order_review_items} without deleting
 *       or modifying the underlying {@link com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder}.</li>
 * </ul>
 * </p>
 */
@Repository
public interface JpaOrderReviewRepository extends JpaRepository<JpaOrderReview, String> {
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths={"items"})
    @Query("select r from JpaOrderReview r order by r.createdAt desc, r.id asc")
    List<JpaOrderReview> findAllWithItems();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from JpaOrderReview r where r.id = :id")
    java.util.Optional<JpaOrderReview> findForModeration(@Param("id") String id);


    /**
     * Resolves reviews associated with the specified order ID ordered by creation time descending.
     *
     * @param orderId primary key of the associated customer order
     * @return a list containing matching reviews (at most one due to unique constraint), or empty
     */
    @Query("SELECT r FROM JpaOrderReview r WHERE r.order.id = :orderId ORDER BY r.createdAt DESC")
    List<JpaOrderReview> findByOrderIdOrderByCreatedAtDesc(@Param("orderId") String orderId);

    /**
     * Nested property traversal alternative for finding reviews by order ID.
     *
     * @param orderId primary key of the associated customer order
     * @return a list containing matching reviews, or empty
     */
    List<JpaOrderReview> findByOrder_IdOrderByCreatedAtDesc(String orderId);

    /**
     * Checks whether a review exists for the specified order ID.
     *
     * @param orderId primary key of the associated customer order
     * @return {@code true} if a review exists, {@code false} otherwise
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN TRUE ELSE FALSE END FROM JpaOrderReview r WHERE r.order.id = :orderId")
    boolean existsByOrderId(@Param("orderId") String orderId);

    /**
     * Nested property traversal alternative for review existence check.
     *
     * @param orderId primary key of the associated customer order
     * @return {@code true} if a review exists, {@code false} otherwise
     */
    boolean existsByOrder_Id(String orderId);
}
