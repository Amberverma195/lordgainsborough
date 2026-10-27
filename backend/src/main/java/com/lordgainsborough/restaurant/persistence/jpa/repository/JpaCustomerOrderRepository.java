package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link JpaCustomerOrder} entities in {@code customer_orders}.
 * <p>
 * Mirrors all query and counter methods defined on the MongoDB {@code CustomerOrderRepository}:
 * <ul>
 *   <li>{@link #findByPickupDateOrderByCreatedAtDesc(String)}</li>
 *   <li>{@link #findByCustomerEmailOrderByCreatedAtDesc(String)}</li>
 *   <li>{@link #findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(String)}</li>
 *   <li>{@link #findByCustomerPhoneOrderByCreatedAtDesc(String)}</li>
 *   <li>{@link #countByCustomerEmailIgnoreCase(String)}</li>
 *   <li>{@link #countByCustomerPhone(String)}</li>
 *   <li>{@link #countByCustomerEmailIgnoreCaseAndStatus(String, String)}</li>
 *   <li>{@link #countByCustomerPhoneAndStatus(String, String)}</li>
 * </ul>
 * </p>
 * <p>
 * <b>Query & Integrity Semantics:</b>
 * <ul>
 *   <li>Preserves exact vs case-insensitive email matching.</li>
 *   <li>Orders results deterministically by {@code createdAt DESC}.</li>
 *   <li>Guest orders are fully supported without requiring registered user joins.</li>
 *   <li>Sensitive tracking token hash is ignored during serialization via entity definition.</li>
 *   <li>Child collections ({@code items}, {@code messages}) retain cascade integrity.</li>
 *   <li>Targeted query methods with {@link EntityGraph} provide targeted eager fetching without
 *       forcing global EAGER loading or causing N+1 query overhead.</li>
 * </ul>
 * </p>
 */
@Repository
public interface JpaCustomerOrderRepository extends JpaRepository<JpaCustomerOrder, String> {

    /** One contact query for all selected users, including guest orders; no user FK assumed. */
    @org.springframework.data.jpa.repository.Query("select o from JpaCustomerOrder o where lower(o.customerEmail) in :emails or o.customerPhone in :phones order by o.createdAt desc, o.id asc")
    List<JpaCustomerOrder> findAdminHistoryByContacts(
            @org.springframework.data.repository.query.Param("emails") List<String> emails,
            @org.springframework.data.repository.query.Param("phones") List<String> phones);

    @EntityGraph(attributePaths = {"items"})
    @org.springframework.data.jpa.repository.Query("select o from JpaCustomerOrder o where o.id in :ids")
    List<JpaCustomerOrder> findAdminHistoryItemsByIds(
            @org.springframework.data.repository.query.Param("ids") List<String> ids);

    @EntityGraph(attributePaths = {"items"})
    @org.springframework.data.jpa.repository.Query("select o from JpaCustomerOrder o where o.pickupDate = :date order by o.createdAt desc, o.id asc")
    List<JpaCustomerOrder> findKdsWithItemsByPickupDate(@org.springframework.data.repository.query.Param("date") String date);

    @EntityGraph(attributePaths = {"items"})
    @org.springframework.data.jpa.repository.Query("select o from JpaCustomerOrder o order by o.createdAt desc, o.id asc")
    List<JpaCustomerOrder> findAllKdsWithItems();

    @EntityGraph(attributePaths = {"messages"})
    @org.springframework.data.jpa.repository.Query("select o from JpaCustomerOrder o where o.id in :ids")
    List<JpaCustomerOrder> findKdsMessagesByIds(@org.springframework.data.repository.query.Param("ids") List<String> ids);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from JpaCustomerOrder o where o.id = :id")
    Optional<JpaCustomerOrder> findForMessageAppend(@org.springframework.data.repository.query.Param("id") String id);

    List<JpaCustomerOrder> findByPickupDateOrderByCreatedAtDesc(String pickupDate);

    List<JpaCustomerOrder> findByCustomerEmailOrderByCreatedAtDesc(String customerEmail);

    List<JpaCustomerOrder> findByCustomerEmailIgnoreCaseOrderByCreatedAtDesc(String customerEmail);

    List<JpaCustomerOrder> findByCustomerPhoneOrderByCreatedAtDesc(String customerPhone);

    long countByCustomerEmailIgnoreCase(String customerEmail);

    long countByCustomerPhone(String customerPhone);

    long countByCustomerEmailIgnoreCaseAndStatus(String customerEmail, String status);

    long countByCustomerPhoneAndStatus(String customerPhone, String status);

    /**
     * Targeted query method fetching an order with child items in a single query via entity graph.
     * Prevents N+1 queries when order line items are needed.
     *
     * @param id order primary key
     * @return an {@link Optional} containing the order with loaded items, or empty
     */
    @EntityGraph(attributePaths = {"items"})
    Optional<JpaCustomerOrder> findWithItemsById(String id);

    /**
     * Targeted query method fetching an order with chat messages in a single query via entity graph.
     *
     * @param id order primary key
     * @return an {@link Optional} containing the order with loaded messages, or empty
     */
    @EntityGraph(attributePaths = {"messages"})
    Optional<JpaCustomerOrder> findWithMessagesById(String id);
}
