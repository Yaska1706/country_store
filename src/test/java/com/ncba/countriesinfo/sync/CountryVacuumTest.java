package com.ncba.countriesinfo.sync;

import com.ncba.countriesinfo.repository.CountryStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryVacuumTest {

    @Mock
    private CountryStoreRepository storeRepository;

    @Test
    void vacuumPurgesWithTheConfiguredRetention() {
        CountryVacuum vacuum = new CountryVacuum(storeRepository, Duration.ofDays(7));
        when(storeRepository.purgeDeletedCountries(Duration.ofDays(7))).thenReturn(2);

        vacuum.vacuum();

        verify(storeRepository, times(1)).purgeDeletedCountries(Duration.ofDays(7));
    }

    @Test
    void vacuumSkipsPurgingWhenNothingExpired() {
        CountryVacuum vacuum = new CountryVacuum(storeRepository, Duration.ofDays(7));

        vacuum.vacuum();

        verify(storeRepository, times(1)).purgeDeletedCountries(Duration.ofDays(7));
    }
}
