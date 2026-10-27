package com.lordgainsborough.restaurant.persistence.jpa.repository;

import com.lordgainsborough.restaurant.persistence.jpa.entity.JpaReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link JpaReservation} entities in {@code reservations}.
 * <p>
 * Inherits standard CRUD operations matching the legacy {@code ReservationRepository} contract:
 * <ul>
 *   <li>{@link #save(Object)}: Persists guest and registered reservations without mandatory user relationships.</li>
 *   <li>{@link #findById(Object)}: Resolves a reservation by primary key.</li>
 *   <li>{@link #findAll()}: Lists all reservations.</li>
 *   <li>{@link #deleteById(Object)} / {@link #delete(Object)}: Removes reservation records.</li>
 * </ul>
 * </p>
 * <p>
 * <b>Integrity & Constraint Semantics:</b>
 * <ul>
 *   <li>Date ({@code date}) and time ({@code time}) strings retain exact input values without time-zone shifting.</li>
 *   <li>Party size check constraint {@code chk_reservations_party_size (party_size > 0)} is enforced by PostgreSQL.</li>
 * </ul>
 * </p>
 */
@Repository
public interface JpaReservationRepository extends JpaRepository<JpaReservation, String> {
    List<JpaReservation> findAllByOrderByCreatedAtDescIdAsc();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from JpaReservation r where r.id = :id")
    java.util.Optional<JpaReservation> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);


    List<JpaReservation> findByDateOrderByTimeAsc(String date);

    List<JpaReservation> findByEmailIgnoreCaseOrderByCreatedAtDesc(String email);

    List<JpaReservation> findByStatus(String status);
}
