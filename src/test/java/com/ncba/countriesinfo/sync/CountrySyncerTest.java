package com.ncba.countriesinfo.sync;

import com.ncba.countriesinfo.client.CountryInfoResult;
import com.ncba.countriesinfo.client.SoapClient;
import com.ncba.countriesinfo.client.SoapLanguage;
import com.ncba.countriesinfo.entity.SyncEntity;
import com.ncba.countriesinfo.entity.SyncLog;
import com.ncba.countriesinfo.entity.SyncStatus;
import com.ncba.countriesinfo.repository.CountryStoreRepository;
import com.ncba.countriesinfo.repository.SyncLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountrySyncerTest {

    @Mock
    private SoapClient soapClient;
    @Mock
    private CountryStoreRepository storeRepository;
    @Mock
    private SyncLogRepository syncLogRepository;

    private SimpleMeterRegistry meterRegistry;
    private CountrySyncer syncer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        syncer = new CountrySyncer(soapClient, storeRepository, syncLogRepository, meterRegistry);
    }

    private static CountryInfoResult result(String iso, String name) {
        CountryInfoResult result = new CountryInfoResult();
        result.setIsoCode(iso);
        result.setName(name);
        result.setLanguages(List.of(new SoapLanguage("en", "English")));
        return result;
    }

    @Test
    void syncUpsertsEveryCountryAndRecordsCountryAndLanguageLogs() {
        when(soapClient.getAllCountriesInfo())
                .thenReturn(List.of(result("AA", "Country A"), result("BB", "Country B")));
        when(storeRepository.upsert(any()))
                .thenReturn(new CountryStoreRepository.UpsertOutcome(
                        CountryStoreRepository.SaveOutcome.INSERTED,
                        new CountryStoreRepository.LanguageChanges(1, 0, 0)))
                .thenReturn(new CountryStoreRepository.UpsertOutcome(
                        CountryStoreRepository.SaveOutcome.SKIPPED,
                        new CountryStoreRepository.LanguageChanges(0, 0, 0)));

        syncer.sync();

        ArgumentCaptor<SyncLog> captor = ArgumentCaptor.forClass(SyncLog.class);
        verify(syncLogRepository, times(4)).save(captor.capture());
        List<SyncLog> logs = captor.getAllValues();

        assertThat(logs).extracting(SyncLog::getEntityType)
                .containsExactly(SyncEntity.COUNTRY, SyncEntity.LANGUAGE, SyncEntity.COUNTRY, SyncEntity.LANGUAGE);
        assertThat(logs).extracting(SyncLog::getStatus)
                .containsOnly(SyncStatus.SUCCESS);
        assertThat(logs.get(0).getMessage()).isEqualTo("inserted");
        assertThat(logs.get(1).getMessage()).isEqualTo("added 1, updated 0, deleted 0");
        assertThat(logs.get(2).getMessage()).isEqualTo("skipped: no changes");
        assertThat(logs.get(3).getMessage()).isEqualTo("skipped: no changes");

        Counter success = meterRegistry.find("sync.runs").tag("result", "success").counter();
        assertThat(success).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1));
    }

    @Test
    void perCountryFailureIsRecordedWithoutAbortingTheRun() {
        when(soapClient.getAllCountriesInfo()).thenReturn(List.of(result("AA", "Country A")));
        when(storeRepository.upsert(any())).thenThrow(new IllegalStateException("boom"));

        syncer.sync();

        ArgumentCaptor<SyncLog> captor = ArgumentCaptor.forClass(SyncLog.class);
        verify(syncLogRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEntityType()).isEqualTo(SyncEntity.COUNTRY);
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(captor.getValue().getMessage()).isEqualTo("boom");

        Counter success = meterRegistry.find("sync.runs").tag("result", "success").counter();
        assertThat(success).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1));
    }

    @Test
    void fetchFailureIsRecordedAsErrorRun() {
        when(soapClient.getAllCountriesInfo()).thenThrow(new RuntimeException("upstream down"));

        syncer.sync();

        Counter error = meterRegistry.find("sync.runs").tag("result", "error").counter();
        assertThat(error).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1));
        verify(syncLogRepository, times(0)).save(any());
    }
}
