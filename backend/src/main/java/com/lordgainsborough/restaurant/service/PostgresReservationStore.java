package com.lordgainsborough.restaurant.service;
import com.lordgainsborough.restaurant.model.Reservation;
import com.lordgainsborough.restaurant.persistence.jpa.repository.JpaReservationRepository;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
public class PostgresReservationStore implements ReservationStore {
    private final JpaReservationRepository repository;
    private final TransactionTemplate transactions;
    private final ReservationMapper mapper=new ReservationMapper();
    private final Clock clock;
    public PostgresReservationStore(JpaReservationRepository repository,JpaTransactionManager manager,Clock clock) {
        this.repository=repository; this.clock=clock.withZone(ZoneId.systemDefault());
        transactions=new TransactionTemplate(manager); transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public List<Reservation> all() { return transactions.execute(tx -> repository.findAllByOrderByCreatedAtDescIdAsc().stream().map(mapper::fromJpa).toList()); }
    public Reservation create(Reservation request) {
        var entity=mapper.toJpa(request); entity.setCreatedAt(LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        return transactions.execute(tx -> mapper.fromJpa(repository.saveAndFlush(entity)));
    }
    public void delete(String id) {
        transactions.executeWithoutResult(tx -> repository.findForUpdate(id).ifPresent(row -> { repository.delete(row); repository.flush(); }));
    }
    public Optional<Reservation> status(String id,Map<String,String> body) {
        return transactions.execute(tx -> repository.findForUpdate(id).map(row -> {
            if (body.containsKey("status")) { ReservationMapper.validateStatus(body); row.setStatus(body.get("status")); repository.flush(); }
            return mapper.fromJpa(row);
        }));
    }
}
