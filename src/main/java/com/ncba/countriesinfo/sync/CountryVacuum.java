package com.ncba.countriesinfo.sync;

import com.ncba.countriesinfo.repository.CountryStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Vacuum policy for soft-deleted countries: rows deleted more than the
 * retention period ago are hard-deleted (languages go with them via the
 * foreign key cascade, sync logs are cleaned up explicitly). Until then a
 * delete can be inspected or manually rolled back via the database.
 */
@Component
public class CountryVacuum {

    private static final Logger log = LoggerFactory.getLogger(CountryVacuum.class);

    private final CountryStoreRepository storeRepository;
    private final Duration retention;

    public CountryVacuum(CountryStoreRepository storeRepository,
                         @Value("${app.vacuum.retention:7d}") Duration retention) {
        this.storeRepository = storeRepository;
        this.retention = retention;
    }

    @Scheduled(fixedRateString = "${app.vacuum.interval:3600000}",
            initialDelayString = "${app.vacuum.initial-delay:60000}")
    public void vacuum() {
        int purged = storeRepository.purgeDeletedCountries(retention);
        if (purged > 0) {
            log.info("Vacuumed {} soft-deleted countries older than {}", purged, retention);
        }
    }
}
