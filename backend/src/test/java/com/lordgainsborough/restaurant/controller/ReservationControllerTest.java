
package com.lordgainsborough.restaurant.controller;
import com.lordgainsborough.restaurant.model.Reservation;
import com.lordgainsborough.restaurant.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReservationControllerTest {
    private final ReservationRepository repository=mock(ReservationRepository.class);
    private final ReservationController controller=new ReservationController(repository);
    private Reservation request() {var r=new Reservation();r.setName("Guest");r.setEmail("guest@example.com");r.setPhone("5195551212");r.setDate("Nov 1");r.setTime("7 PM");r.setPartySize(2);return r;}
    @Test void defaultMongoPublicCreateReplacesClientOwnedFieldsAndPreservesShape() {
        var r=request();r.setId("client");r.setStatus("APPROVED");r.setCreatedAt(java.time.LocalDateTime.MIN);when(repository.save(any())).thenAnswer(call->call.getArgument(0));
        var saved=controller.createReservation(r);assertThat(saved.getId()).isNull();assertThat(saved.getStatus()).isEqualTo("PENDING");assertThat(saved.getCreatedAt()).isAfter(java.time.LocalDateTime.now().minusMinutes(1));assertThat(saved.getDate()).isEqualTo("Nov 1");verify(repository).save(r);
    }
    @Test void invalidPublicInputNeverSaves() {
        var r=request();r.setPhone(null);assertThatThrownBy(()->controller.createReservation(r)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(repository);
    }
    @Test void arbitraryStatusPreservedAndMissingKeyDoesNotWrite() {
        var r=request();when(repository.findById("id")).thenReturn(Optional.of(r));when(repository.save(r)).thenReturn(r);
        assertThat(controller.updateReservationStatus("id",Map.of())).isSameAs(r);verify(repository,never()).save(any());
        assertThat(controller.updateReservationStatus("id",Map.of("status","custom value")).getStatus()).isEqualTo("custom value");verify(repository).save(r);
    }
    @Test void nullStatusCannotCorruptExistingRecord() {
        var r=request();when(repository.findById("id")).thenReturn(Optional.of(r));assertThatThrownBy(()->controller.updateReservationStatus("id",Collections.singletonMap("status",null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(r.getStatus()).isEqualTo("PENDING");verify(repository,never()).save(any());
    }
    @Test void missingPatchHasControlled404AndDeleteRetainsLegacyCall() {
        when(repository.findById("missing")).thenReturn(Optional.empty());assertThatThrownBy(()->controller.updateReservationStatus("missing",Map.of())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).satisfies(error->assertThat(((org.springframework.web.server.ResponseStatusException)error).getStatusCode().value()).isEqualTo(404));
        controller.deleteReservation("missing");verify(repository).deleteById("missing");
    }
    @Test void listingUsesOriginalDescendingSort() {
        when(repository.findAll(any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(request()));assertThat(controller.getAllReservations()).hasSize(1);verify(repository).findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,"createdAt"));
    }
}
