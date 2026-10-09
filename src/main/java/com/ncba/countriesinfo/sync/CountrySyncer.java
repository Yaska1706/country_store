package com.ncba.countriesinfo.sync;

import com.ncba.countriesinfo.client.CountryInfoResult;
import com.ncba.countriesinfo.client.SoapClient;
import com.ncba.countriesinfo.converter.CountryConverter;
import com.ncba.countriesinfo.dto.CountryInfoResponse;
import com.ncba.countriesinfo.entity.SyncEntity;
import com.ncba.countriesinfo.entity.SyncLog;
import com.ncba.countriesinfo.entity.SyncStatus;
import com.ncba.countriesinfo.repository.CountryStoreRepository;
import com.ncba.countriesinfo.repository.SyncLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Background sync : one upsert per upstream country,
 * per-country failures are logged and counted without aborting the run, and
 * every outcome is recorded as country + language rows in the sync log.
 */
@Component
public class CountrySyncer {

    private static final Logger log = LoggerFactory.getLogger(CountrySyncer.class);

    private final SoapClient soapClient;
    private final CountryStoreRepository storeRepository;
    private final SyncLogRepository syncLogRepository;
    private final MeterRegistry meterRegistry;

    public CountrySyncer(SoapClient soapClient,
                         CountryStoreRepository storeRepository,
                         SyncLogRepository syncLogRepository,
                         MeterRegistry meterRegistry) {
        this.soapClient = soapClient;
        this.storeRepository = storeRepository;
        this.syncLogRepository = syncLogRepository;
        this.meterRegistry = meterRegistry;
    }

    private static final class SyncStats {
        int total;
        int inserted;
        int updated;
        int skipped;
        int failed;
    }

    @Scheduled(fixedRateString = "${app.sync.interval}", initialDelayString = "${app.sync.initial-delay}")
    public void sync() {
        long startedAt = System.currentTimeMillis();
        try {
            SyncStats stats = syncCountries();
            long duration = System.currentTimeMillis() - startedAt;
            meterRegistry.counter("sync.runs", "result", "success").increment();
            log.info("Country sync finished: total={} inserted={} updated={} skipped={} failed={} duration={}ms",
                    stats.total, stats.inserted, stats.updated, stats.skipped, stats.failed, duration);
        } catch (Exception e) {
            meterRegistry.counter("sync.runs", "result", "error").increment();
            log.error("Country sync failed", e);
        }
    }

    private SyncStats syncCountries() {
        List<CountryInfoResult> countries = soapClient.getAllCountriesInfo();
        SyncStats stats = new SyncStats();
        stats.total = countries.size();

        for (CountryInfoResult result : countries) {
            CountryInfoResponse info = CountryConverter.toResponse(result);
            try {
                CountryStoreRepository.UpsertOutcome outcome = storeRepository.upsert(info);
                switch (outcome.country()) {
                    case INSERTED -> stats.inserted++;
                    case UPDATED -> stats.updated++;
                    case SKIPPED -> stats.skipped++;
                }
                recordSuccess(info.getIsoCode(), outcome);
            } catch (Exception e) {
                stats.failed++;
                log.error("Country upsert failed: country_iso={}", info.getIsoCode(), e);
                recordSyncLog(SyncEntity.COUNTRY, info.getIsoCode(), SyncStatus.FAILED,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        return stats;
    }

    private void recordSuccess(String countryIsoCode, CountryStoreRepository.UpsertOutcome outcome) {
        String countryMessage = outcome.country() == CountryStoreRepository.SaveOutcome.SKIPPED
                ? "skipped: no changes"
                : outcome.country().name().toLowerCase();

        String languageMessage = "skipped: no changes";
        CountryStoreRepository.LanguageChanges languages = outcome.languages();
        if (!languages.unchanged()) {
            languageMessage = "added %d, updated %d, deleted %d"
                    .formatted(languages.added(), languages.updated(), languages.deleted());
        }

        recordSyncLog(SyncEntity.COUNTRY, countryIsoCode, SyncStatus.SUCCESS, countryMessage);
        recordSyncLog(SyncEntity.LANGUAGE, countryIsoCode, SyncStatus.SUCCESS, languageMessage);
    }

    private void recordSyncLog(SyncEntity entity, String countryIsoCode, SyncStatus status, String message) {
        try {
            SyncLog syncLog = new SyncLog();
            syncLog.setEntityType(entity);
            syncLog.setCountryIsoCode(countryIsoCode);
            syncLog.setStatus(status);
            syncLog.setMessage(message);
            syncLogRepository.save(syncLog);
        } catch (Exception e) {
            log.error("Failed to record sync log: country_iso={}", countryIsoCode, e);
        }
    }
}
