package com.lordgainsborough.restaurant.service;

import com.lordgainsborough.restaurant.model.CustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaCustomerOrder;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaCustomerOrderRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/** Same PG-017 parent lock and dataset; no stale domain graph is ever merged back into an order. */
public class PostgresKdsOrderStore implements KdsOrderStore {
    private final JpaCustomerOrderRepository orders;
    private final PostgresCustomerNoShowStore noShows;
    private final CustomerOrderPresentationService presentation;
    private final CustomerOrderMapper mapper = new CustomerOrderMapper();
    private final JpaTransactionManager manager;
    private final TransactionTemplate transactions;
    private final Clock clock;
    public PostgresKdsOrderStore(JpaCustomerOrderRepository orders, PostgresCustomerNoShowStore noShows,
            CustomerOrderPresentationService presentation, JpaTransactionManager manager, Clock clock) {
        if (noShows.getTransactionManager() != manager) throw new IllegalArgumentException("KDS and no-show writes must use the same transaction manager");
        this.orders = orders; this.noShows = noShows; this.presentation = presentation; this.manager = manager;
        this.clock = clock.withZone(ZoneId.systemDefault());
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public EntityManagerFactory getEntityManagerFactory() { return manager.getEntityManagerFactory(); }
    public PostgresCustomerNoShowStore getNoShowStore() { return noShows; }
    public CustomerOrderPresentationService getPresentationService() { return presentation; }

    private List<CustomerOrder> materialize(List<JpaCustomerOrder> entities) {
        // Separate, chunked collection fetches avoid both N+1 and items x messages Cartesian expansion.
        for (int from=0;from<entities.size();from+=256) {
            List<String> ids = entities.subList(from,Math.min(from+256,entities.size())).stream().map(JpaCustomerOrder::getId).toList();
            orders.findKdsMessagesByIds(ids);
        }
        return presentation.attachCustomerOrderCounts(entities.stream().map(mapper::fromJpa).toList());
    }
    public List<CustomerOrder> byPickupDate(String date) {
        return transactions.execute(status -> materialize(orders.findKdsWithItemsByPickupDate(date)));
    }
    public List<CustomerOrder> all() { return transactions.execute(status -> materialize(orders.findAllKdsWithItems())); }

    public Optional<CustomerOrder> status(String id, String next, IntUnaryOperator minutes, Consumer<CustomerOrder> afterCommit) {
        return transactions.execute(status -> orders.findForMessageAppend(id).map(entity -> {
            String previous = entity.getStatus();
            CustomerOrder domain = mapper.fromJpa(entity);
            KdsOrderTransitions.status(domain,next,LocalDateTime.now(clock));
            entity.setStatus(domain.getStatus());
            entity.setAverageReadyMinutes(minutes.applyAsInt(entity.getAverageReadyMinutes()));
            entity.setAcceptedAt(domain.getAcceptedAt()); entity.setReadyAt(domain.getReadyAt());
            entity.setPickedUpAt(domain.getPickedUpAt()); entity.setNoShowAt(domain.getNoShowAt());
            orders.flush();
            if ("NO_SHOW".equals(next) || "NO_SHOW".equalsIgnoreCase(previous)) noShows.recordNoShow(domain);
            return committedSnapshot(entity,afterCommit);
        }));
    }
    public Optional<CustomerOrder> readyTime(String id, int minutes, Consumer<CustomerOrder> afterCommit) {
        return transactions.execute(status -> orders.findForMessageAppend(id).map(entity -> {
            entity.setAverageReadyMinutes(minutes); orders.flush(); return committedSnapshot(entity,afterCommit);
        }));
    }
    public Optional<CustomerOrder.OrderMessage> message(String id, CustomerOrder.OrderMessage message, Consumer<CustomerOrder> afterCommit) {
        return transactions.execute(status -> orders.findForMessageAppend(id).map(entity -> {
            // Initialize the ordered history under the parent lock, before Hibernate can queue an append.
            entity.getMessages().size();
            entity.addMessage(mapper.toJpa(message)); orders.flush();
            committedSnapshot(entity,afterCommit); return message;
        }));
    }
    private CustomerOrder committedSnapshot(JpaCustomerOrder entity, Consumer<CustomerOrder> afterCommit) {
        CustomerOrder saved = presentation.attachCustomerOrderCount(mapper.fromJpa(entity));
        OrderNotifications.afterCommit(() -> afterCommit.accept(saved)); return saved;
    }

    public JpaCustomerOrderRepository getOrderRepository() { return orders; }
}
