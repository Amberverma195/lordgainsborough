package com.lordgainsborough.restaurant.config;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("app.reservations.seating")
public class ReservationSeatingPolicy {
    private Duration lastSeatingBeforeClose = Duration.ofMinutes(60);
    private Duration minimumAdvanceNotice = Duration.ofMinutes(30);
    private boolean managementApproved = true;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDataSource(javax.sql.DataSource source){jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);}
    public ReservationSeatingPolicy current(){
        if(jdbc == null) return this;
        return jdbc.queryForObject("SELECT last_seating_minutes,advance_notice_minutes,management_approved FROM reservation_seating_policy WHERE id='default'", (rs, n) -> {
            var value = new ReservationSeatingPolicy();
            value.setLastSeatingBeforeClose(Duration.ofMinutes(rs.getInt(1)));
            value.setMinimumAdvanceNotice(Duration.ofMinutes(rs.getInt(2)));
            value.setManagementApproved(rs.getBoolean(3));
            return value;
        });
    }
    public Duration getLastSeatingBeforeClose(){return lastSeatingBeforeClose;}
    public Duration getMinimumAdvanceNotice(){return minimumAdvanceNotice;}
    public boolean isManagementApproved(){return managementApproved;}
    public void setLastSeatingBeforeClose(Duration value){lastSeatingBeforeClose = validate(value);}
    public void setMinimumAdvanceNotice(Duration value){minimumAdvanceNotice = validate(value);}
    public void setManagementApproved(boolean value){managementApproved = value;}
    private Duration validate(Duration value){if(value == null || value.isNegative() || value.compareTo(Duration.ofDays(365)) > 0)throw new IllegalArgumentException("Seating durations must be nonnegative and at most 365 days");return value;}
}
