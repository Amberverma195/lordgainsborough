
package com.lordgainsborough.restaurant.controller;
import com.lordgainsborough.restaurant.model.Reservation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PostgresReservationControllerIntegrationTest extends PostgresPg020IntegrationSupport {
    private Reservation request() {
        var r=new Reservation();r.setName("Guest");r.setEmail("guest@example.com");r.setPhone("5195551212");r.setDate("2026-11-01");r.setTime("01:30");r.setPartySize(4);r.setSpecialRequests("window seat");return r;
    }
    @Test void publicCreateIgnoresTamperedFieldsAndRoundTripsEveryFieldWithoutTimezoneShift() throws Exception {
        var request=request();request.setId("client-id");request.setStatus("CONFIRMED");request.setCreatedAt(java.time.LocalDateTime.MIN);
        var response=mvc.perform(post("/api/reservations").contentType("application/json").content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.date").value("2026-11-01")).andExpect(jsonPath("$.time").value("01:30"))
            .andReturn().getResponse().getContentAsString();
        var dto=json.readValue(response,Reservation.class);assertThat(json.readTree(response).size()).isEqualTo(10);assertThat(dto.getId()).isNotEqualTo("client-id").hasSize(36);
        assertThat(dto.getCreatedAt()).isAfter(java.time.LocalDateTime.now().minusMinutes(1));assertThat(count("users")).isZero();
        assertThat(jdbc.queryForObject("SELECT created_at FROM reservations WHERE id=?",java.time.LocalDateTime.class,dto.getId())).isEqualTo(dto.getCreatedAt());
        mvc.perform(get("/api/reservations").header("Authorization","Bearer admin-fixture")).andExpect(status().isOk()).andExpect(content().json("["+response+"]"));
    }
    static Stream<Arguments> invalidInputs() {
        var result=new ArrayList<Arguments>();
        for(String field:List.of("name","email","phone","date","time"))for(String value:Arrays.asList(null,""," "))result.add(Arguments.of(field,value));
        for(String field:List.of("name","email","phone","date","time"))result.add(Arguments.of(field,"x".repeat(field.equals("phone")?51:field.equals("date")||field.equals("time")?21:256)));
        result.add(Arguments.of("partySize",0));result.add(Arguments.of("partySize",-1));result.add(Arguments.of("specialRequests","bad"+(char)0));return result.stream();
    }
    @ParameterizedTest @MethodSource("invalidInputs") void invalidInputsReturn400WithNoReservationOrOtherWrites(String field,Object value) throws Exception {
        var payload=json.convertValue(request(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});payload.put(field,value);
        mvc.perform(post("/api/reservations").contentType("application/json").content(json.writeValueAsString(payload))).andExpect(status().isBadRequest());assertThat(count("reservations")).isZero();assertThat(count("users")).isZero();
    }
    @Test void arbitraryRepresentableDateTimeAndStatusStringsRemainAccepted() {
        var request=request();request.setDate("Sat Nov 1");request.setTime("7 PM local");var saved=reservationController.createReservation(request);
        assertThat(saved.getDate()).isEqualTo("Sat Nov 1");assertThat(saved.getTime()).isEqualTo("7 PM local");
        for(String status:List.of("private custom status",""," confirmed "))assertThat(reservationController.updateReservationStatus(saved.getId(),Map.of("status",status)).getStatus()).isEqualTo(status);
    }
    @Test void statusAbsentRetainsWholeRecordAndInvalidStatusCannotPartiallyUpdate() throws Exception {
        var saved=reservationController.createReservation(request());
        assertThat(reservationController.updateReservationStatus(saved.getId(),Map.of())).usingRecursiveComparison().isEqualTo(saved);
        for(String value:Arrays.asList(null,"x".repeat(51),"bad"+(char)0)) { String body=json.writeValueAsString(Collections.singletonMap("status",value));
            mvc.perform(patch("/api/reservations/"+saved.getId()+"/status").header("Authorization","Bearer admin-fixture").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        assertThat(reservationController.getAllReservations().getFirst()).usingRecursiveComparison().isEqualTo(saved);
        mvc.perform(patch("/api/reservations/missing/status").header("Authorization","Bearer admin-fixture").contentType("application/json").content("{}")).andExpect(status().isNotFound());
    }
    @Test void descendingCreatedAtWithDeterministicIdTieAndIdempotentDeletionDoesNotCascade() throws Exception {
        var order=createOrder();var one=reservationController.createReservation(request());var two=reservationController.createReservation(request());var three=reservationController.createReservation(request());
        jdbc.update("UPDATE reservations SET created_at='2026-01-01'");jdbc.update("UPDATE reservations SET created_at='2026-02-01' WHERE id=?",three.getId());
        var tie=new ArrayList<>(List.of(one.getId(),two.getId()));Collections.sort(tie);
        assertThat(reservationController.getAllReservations()).extracting(Reservation::getId).containsExactly(three.getId(),tie.get(0),tie.get(1));
        for(int i=0;i<2;i++)mvc.perform(delete("/api/reservations/"+one.getId()).header("Authorization","Bearer admin-fixture")).andExpect(status().isOk()).andExpect(content().string(""));
        assertThat(count("reservations")).isEqualTo(2);assertThat(checkout.findById(order.getOrder().getId())).isPresent();
    }
    @Test void administratorOperationsRemainProtectedWhilePostIsPublic() throws Exception {
        mvc.perform(get("/api/reservations")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/reservations/missing")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/reservations/missing/status").contentType("application/json").content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/reservations").header("Authorization","Bearer invalid")).andExpect(status().isNotFound());
    }
    @Test void insertUpdateAndDeleteFailuresRollBackWithoutAffectingOtherTables() {
        failureTrigger("reservations","INSERT",()->{assertThatThrownBy(()->reservationController.createReservation(request())).isInstanceOf(RuntimeException.class);assertThat(count("reservations")).isZero();});
        var saved=reservationController.createReservation(request());
        failureTrigger("reservations","UPDATE",()->{assertThatThrownBy(()->reservationController.updateReservationStatus(saved.getId(),Map.of("status","custom"))).isInstanceOf(RuntimeException.class);assertThat(reservationController.getAllReservations().getFirst()).usingRecursiveComparison().isEqualTo(saved);});
        failureTrigger("reservations","DELETE",()->{assertThatThrownBy(()->reservationController.deleteReservation(saved.getId())).isInstanceOf(RuntimeException.class);assertThat(count("reservations")).isEqualTo(1);});
    }
}
