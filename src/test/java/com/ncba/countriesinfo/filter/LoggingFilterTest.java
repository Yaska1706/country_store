package com.ncba.countriesinfo.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoggingFilterTest {

    @RestController
    static class ProbeController {

        @GetMapping("/health")
        ResponseEntity<String> health(@RequestParam(required = false) String fail) {
            return fail == null ? ResponseEntity.ok("OK") : ResponseEntity.status(500).body("boom");
        }

        @GetMapping("/ready")
        ResponseEntity<String> ready() {
            return ResponseEntity.ok("OK");
        }

        @GetMapping("/countries/7")
        ResponseEntity<String> country() {
            return ResponseEntity.ok("{}");
        }
    }

    private ListAppender<ILoggingEvent> appender;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProbeController())
                .addFilters(new LoggingFilter(new SimpleMeterRegistry()))
                .build();

        Logger filterLogger = (Logger) LoggerFactory.getLogger(LoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(LoggingFilter.class)).detachAppender(appender);
        MDC.clear();
    }

    @Test
    void successfulProbeRequestsAreNotLogged() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/ready")).andExpect(status().isOk());

        assertThat(appender.list).isEmpty();
    }

    @Test
    void failingProbeRequestsAreStillLogged() throws Exception {
        mockMvc.perform(get("/health").param("fail", "true"))
                .andExpect(status().isInternalServerError());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("status=500");
    }

    @Test
    void regularRequestsAreLoggedWithRequestContext() throws Exception {
        mockMvc.perform(get("/countries/7")).andExpect(status().isOk());

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFormattedMessage()).contains("method=GET");
        assertThat(events.get(0).getFormattedMessage()).contains("status=200");
    }

    @Test
    void mdcIsClearedAfterEachRequest() throws Exception {
        mockMvc.perform(get("/countries/7")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isOk());

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("method")).isNull();
        assertThat(MDC.get("route")).isNull();
        assertThat(MDC.get("ip")).isNull();
    }
}
