package com.lordgainsborough.restaurant.controller;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReadinessControllerTest {
 @Test void startupNotReadyDoesNotQueryDatabase(){var availability=mock(ApplicationAvailability.class);var source=mock(DataSource.class);when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);assertThat(new ReadinessController(availability,source).readiness().getStatusCode().value()).isEqualTo(503);verifyNoInteractions(source);}
 @Test void databaseFailureIsNotReportedHealthy() throws Exception {var availability=mock(ApplicationAvailability.class);var source=mock(DataSource.class);when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);when(source.getConnection()).thenThrow(new java.sql.SQLException("offline"));assertThat(new ReadinessController(availability,source).readiness().getStatusCode().value()).isEqualTo(503);}
}
