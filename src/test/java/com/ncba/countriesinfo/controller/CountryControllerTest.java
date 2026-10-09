package com.ncba.countriesinfo.controller;

import com.ncba.countriesinfo.dto.CountryInfoResponse;
import com.ncba.countriesinfo.exception.GlobalExceptionHandler;
import com.ncba.countriesinfo.exception.ResourceNotFoundException;
import com.ncba.countriesinfo.filter.LoggingFilter;
import com.ncba.countriesinfo.service.CountryService;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CountryControllerTest {

    @Mock
    private CountryService countryService;

    private PrometheusMeterRegistry prometheusRegistry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CountryController(countryService, prometheusRegistry))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new LoggingFilter(prometheusRegistry))
                .build();
    }

    private static List<CountryInfoResponse> sampleCountries(int n) {
        List<CountryInfoResponse> countries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CountryInfoResponse country = new CountryInfoResponse();
            country.setId((long) i + 1);
            country.setIsoCode("C%02d".formatted(i));
            country.setName("Country %02d".formatted(i));
            countries.add(country);
        }
        return countries;
    }

    @Test
    void healthReturnsJsonStringOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("\"OK\""));
    }

    @Test
    void readyReturnsOkWhenStoreIsReachable() throws Exception {
        mockMvc.perform(get("/ready"))
                .andExpect(status().isOk())
                .andExpect(content().string("\"OK\""));
    }

    @Test
    void readyReturns503WhenStoreIsDown() throws Exception {
        doThrow(new RuntimeException("db down")).when(countryService).ping();

        mockMvc.perform(get("/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("not ready"));
    }

    @Test
    void metricsExposesPrometheusText() throws Exception {
        mockMvc.perform(get("/health"));

        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("http_requests_total")));
    }

    @Test
    void listCountriesDefaultsToFirstPageOfTwenty() throws Exception {
        when(countryService.listCountries()).thenReturn(sampleCountries(25));

        mockMvc.perform(get("/countries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.total_pages").value(2))
                .andExpect(jsonPath("$.countries.length()").value(20))
                .andExpect(jsonPath("$.countries[0].id").value(1))
                .andExpect(jsonPath("$.countries[0].country_iso_code").value("C00"));
    }

    @Test
    void listCountriesSecondPage() throws Exception {
        when(countryService.listCountries()).thenReturn(sampleCountries(25));

        mockMvc.perform(get("/countries").param("page", "2").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countries.length()").value(10))
                .andExpect(jsonPath("$.countries[0].id").value(11))
                .andExpect(jsonPath("$.total_pages").value(3));
    }

    @Test
    void listCountriesPageBeyondRangeReturnsEmptyArray() throws Exception {
        when(countryService.listCountries()).thenReturn(sampleCountries(5));

        mockMvc.perform(get("/countries").param("page", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countries").isEmpty())
                .andExpect(jsonPath("$.total").value(5));
    }

    @Test
    void listCountriesRejectsInvalidPaginationBeforeReading() throws Exception {
        String[][] cases = {
                {"page", "0", "page must be a positive integer"},
                {"page", "abc", "page must be a positive integer"},
                {"limit", "0", "limit must be a positive integer"},
                {"limit", "101", "limit must not exceed 100"},
        };
        for (String[] c : cases) {
            mockMvc.perform(get("/countries").param(c[0], c[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(c[2]));
        }
        verifyNoInteractions(countryService);
    }

    @Test
    void listCountriesStoreErrorIs500() throws Exception {
        when(countryService.listCountries()).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/countries"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("failed to list countries"));
    }

    @Test
    void getCountryById() throws Exception {
        when(countryService.getCountry(7L)).thenReturn(sampleCountries(1).get(0));

        mockMvc.perform(get("/countries/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
        verify(countryService, times(1)).getCountry(7L);
    }

    @Test
    void getCountryRejectsInvalidId() throws Exception {
        for (String id : new String[]{"abc", "0", "-1", "1.5"}) {
            mockMvc.perform(get("/countries/" + id))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid country id: expected a positive integer"));
        }
        verifyNoInteractions(countryService);
    }

    @Test
    void getCountryNotFoundIs404() throws Exception {
        when(countryService.getCountry(999L)).thenThrow(new ResourceNotFoundException("country not found"));

        mockMvc.perform(get("/countries/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("country not found"));
    }

    @Test
    void getCountryFailureIs500() throws Exception {
        when(countryService.getCountry(7L)).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get("/countries/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("failed to get country"));
    }

    @Test
    void updateCountryPathIdWinsOverBody() throws Exception {
        when(countryService.updateCountry(eq(5L), any())).thenAnswer(inv -> {
            CountryInfoResponse response = new CountryInfoResponse();
            response.setId(inv.getArgument(0));
            response.setName(inv.getArgument(1, CountryInfoResponse.class).getName());
            return response;
        });

        mockMvc.perform(put("/countries/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":999,"country_name":"Germany","capital_city":"Berlin"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.country_name").value("Germany"));

        ArgumentCaptor<CountryInfoResponse> captor = ArgumentCaptor.forClass(CountryInfoResponse.class);
        verify(countryService, times(1)).updateCountry(eq(5L), captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(5L);
    }

    @Test
    void updateCountryRejectsEmptyName() throws Exception {
        mockMvc.perform(put("/countries/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"country_name":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("country name cannot be empty"));
        verifyNoInteractions(countryService);
    }

    @Test
    void updateCountryRejectsMalformedBody() throws Exception {
        mockMvc.perform(put("/countries/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid request body"));
    }

    @Test
    void updateCountryRejectsInvalidId() throws Exception {
        mockMvc.perform(put("/countries/abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"country_name":"X"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid country id: expected a positive integer"));
        verify(countryService, never()).updateCountry(anyLong(), any());
    }

    @Test
    void updateCountryNotFoundIs404() throws Exception {
        when(countryService.updateCountry(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("country not found"));

        mockMvc.perform(put("/countries/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"country_name":"X"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("country not found"));
    }

    @Test
    void updateCountryStoreErrorIs500() throws Exception {
        when(countryService.updateCountry(eq(5L), any())).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(put("/countries/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"country_name":"United States"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("failed to update country"));
    }

    @Test
    void deleteCountryReturns204() throws Exception {
        mockMvc.perform(delete("/countries/42"))
                .andExpect(status().isNoContent());
        verify(countryService, times(1)).deleteCountry(42L);
    }

    @Test
    void deleteCountryNotFoundIs404() throws Exception {
        doThrow(new ResourceNotFoundException("country not found")).when(countryService).deleteCountry(999L);

        mockMvc.perform(delete("/countries/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("country not found"));
    }

    @Test
    void deleteCountryInvalidIdIs400() throws Exception {
        mockMvc.perform(delete("/countries/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid country id: expected a positive integer"));
        verify(countryService, never()).deleteCountry(anyLong());
    }

    @Test
    void countryInfoTitleCasesAndLooksUpByName() throws Exception {
        when(countryService.lookupByName("New York")).thenReturn(sampleCountries(1).get(0));

        mockMvc.perform(post("/country")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  new york "}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(countryService, times(1)).lookupByName(captor.capture());
        assertThat(captor.getValue()).isEqualTo("New York");
    }

    @Test
    void countryInfoEmptyNameIs400() throws Exception {
        mockMvc.perform(post("/country")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("country name cannot be empty"));
        verifyNoInteractions(countryService);
    }

    @Test
    void countryInfoMalformedBodyIs400() throws Exception {
        mockMvc.perform(post("/country")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid request body"));
    }

    @Test
    void countryInfoNotFoundIs404() throws Exception {
        when(countryService.lookupByName("Atlantis"))
                .thenThrow(new ResourceNotFoundException("country not found"));

        mockMvc.perform(post("/country")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Atlantis"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("country not found"));
    }

    @Test
    void countryInfoFailureIs500() throws Exception {
        when(countryService.lookupByName("Kenya")).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post("/country")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Kenya"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("failed to get country info"));
    }
}
