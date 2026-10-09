package com.ncba.countriesinfo.repository;

import com.ncba.countriesinfo.dto.CountryInfoResponse;
import com.ncba.countriesinfo.dto.LanguageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryStoreRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private CountryStoreRepository repository;

    @BeforeEach
    void setUp() {
        repository = new CountryStoreRepository(jdbcTemplate);
    }

    private static CountryInfoResponse country(String iso, List<LanguageDto> languages) {
        CountryInfoResponse info = new CountryInfoResponse();
        info.setIsoCode(iso);
        info.setName("Test");
        info.setLanguages(languages);
        return info;
    }

    @Test
    void upsertSkipsSoftDeletedCountriesWithoutWriting() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq("DE"))).thenReturn(true);

        CountryStoreRepository.UpsertOutcome outcome = repository.upsert(country("DE", List.of()));

        assertThat(outcome.country()).isEqualTo(CountryStoreRepository.SaveOutcome.SKIPPED);
        assertThat(outcome.languages().unchanged()).isTrue();
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void upsertActiveCountryInsertsAndReconcilesLanguagesByCountryId() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq("KE"))).thenReturn(false);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("KE"))).thenReturn(5L);
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq(5L)))
                .thenReturn(new LinkedHashMap<String, String>());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        CountryStoreRepository.UpsertOutcome outcome = repository.upsert(
                country("KE", List.of(new LanguageDto("swa", "Swahili"))));

        assertThat(outcome.country()).isEqualTo(CountryStoreRepository.SaveOutcome.INSERTED);
        assertThat(outcome.languages().added()).isEqualTo(1);
        assertThat(outcome.languages().updated()).isZero();
        assertThat(outcome.languages().deleted()).isZero();
        verify(jdbcTemplate).batchUpdate(anyString(), any(Collection.class), eq(1),
                any(ParameterizedPreparedStatementSetter.class));
    }

    @Test
    void purgeDeletedCountriesRemovesSyncLogsThenCountries() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(3).thenReturn(2);

        int purged = repository.purgeDeletedCountries(Duration.ofDays(7));

        assertThat(purged).isEqualTo(2);
    }
}
