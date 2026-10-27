package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.*;
import com.lordgainsborough.restaurant.persistence.jpa.repository.*;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
/** Review children and the managed order flag commit together under the shared parent lock. */
public class PostgresReviewStore implements ReviewStore {
    private final JpaCustomerOrderRepository orders;
    private final JpaOrderReviewRepository reviews;
    private final PostgresCustomerOrderStore checkout;
    private final OrderAccessService access;
    private final JpaTransactionManager manager;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ReviewMapper mapper=new ReviewMapper();
    private final CustomerOrderMapper orderMapper=new CustomerOrderMapper();
    private final CustomerOrderPresentationService presentation;
    public PostgresReviewStore(JpaCustomerOrderRepository orders, JpaOrderReviewRepository reviews,
            PostgresCustomerOrderStore checkout, OrderAccessService access, JpaTransactionManager manager, Clock clock) {
        if (checkout.getEntityManagerFactory()!=manager.getEntityManagerFactory()) throw new IllegalArgumentException("Review/checkout persistence unit mismatch");
        this.orders=orders; this.reviews=reviews; this.checkout=checkout; this.access=access; this.manager=manager;
        this.clock=clock.withZone(ZoneId.systemDefault());
        this.presentation=new CustomerOrderPresentationService(new PostgresCustomerOrderCountStore(orders,manager.getEntityManagerFactory()));
        transactions=new TransactionTemplate(manager); transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public EntityManagerFactory getEntityManagerFactory() { return manager.getEntityManagerFactory(); }
    public PostgresCustomerOrderStore getOrderStore() { return checkout; }
    public OrderAccessService getOrderAccessService() { return access; }
    public boolean usesPostgres() { return true; }
    public Creation create(String id, String authorization, String token, OrderReview request, Consumer<CustomerOrder> committed) {
        return transactions.execute(tx -> orders.findForMessageAppend(id).map(order -> {
            var domain=orderMapper.fromJpa(order);
            // The access service resolves only the selected PostgreSQL identity, or the guest tracking token.
            if (!access.canAccessPostgresOrder(domain,authorization,token)) return new Creation(404,null);
            if (order.isReviewed() || reviews.existsByOrderId(id)) return new Creation(409,null);
            if (request != null && request.getItems() != null) for (var item : request.getItems()) {
                if (item != null) PostgresInput.text(item.getDescription(),Integer.MAX_VALUE,false);
            }
            var items=ReviewItems.normalize(request,domain); if (items.isEmpty()) return new Creation(400,null);
            var saved=reviews.saveAndFlush(mapper.toJpa(ReviewItems.serverReview(domain,items,LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS)),order));
            order.setReviewed(true); orders.flush();
            var snapshot=presentation.attachCustomerOrderCount(orderMapper.fromJpa(order));
            var result=mapper.fromJpa(saved);
            OrderNotifications.afterCommit(() -> committed.accept(snapshot)); return new Creation(200,result);
        }).orElseGet(() -> new Creation(404,null)));
    }
    public List<OrderReview> all() {
        return transactions.execute(tx -> reviews.findAllWithItems().stream().map(mapper::fromJpa).toList());
    }
    public Optional<OrderReview> moderate(String id,String status) {
        return transactions.execute(tx -> reviews.findForModeration(id).map(review -> {
            review.setStatus(status); review.setUpdatedAt(LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS)); reviews.flush(); return mapper.fromJpa(review);
        }));
    }
}
