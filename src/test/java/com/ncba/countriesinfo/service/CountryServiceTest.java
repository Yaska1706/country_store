package com.ncba.countriesinfo.service;

import com.ncba.countriesinfo.client.CountryInfoResult;
import com.ncba.countriesinfo.client.SoapClient;
import com.ncba.countriesinfo.client.SoapLanguage;
import com.ncba.countriesinfo.dto.CountryInfoResponse;
import com.ncba.countriesinfo.dto.LanguageDto;
import com.ncba.countriesinfo.entity.CountryInfo;
import com.ncba.countriesinfo.entity.Language;
import com.ncba.countriesinfo.exception.ResourceNotFoundException;
import com.ncba.countriesinfo.repository.CountryInfoRepository;
import com.ncba.countriesinfo.repository.CountryStoreRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountryServiceTest {

    @Mock
    private CountryInfoRepository countryRepository;
    @Mock
    private CountryStoreRepository storeRepository;
    @Mock
    private SoapClient soapClient;
    @Mock
    private EntityManager entityManager;

    private CountryService service;

    @BeforeEach
    void setUp() {
        service = new CountryService(countryRepository, storeRepository, soapClient,
                new CaffeineCacheManager("countries"), entityManager);
    }

    private static CountryInfo entity(long id, String iso, String name, String... languageNames) {
        CountryInfo country = new CountryInfo();
        country.setId(id);
        country.setIsoCode(iso);
        country.setName(name);
        country.setCapitalCity("");
        country.setPhoneCode("");
        country.setContinentCode("");
        country.setCurrencyIsoCode("");
        country.setCountryFlag("");
        for (int i = 0; i < languageNames.length; i += 2) {
            Language lang = new Language();
            lang.setIsoCode(languageNames[i]);
            lang.setName(languageNames[i + 1]);
            lang.setCountryInfo(country);
            country.getLanguages().add(lang);
        }
        return country;
    }

    private static CountryInfoResult upstreamResult(String iso, String name) {
        CountryInfoResult result = new CountryInfoResult();
        result.setIsoCode(iso);
        result.setName(name);
        result.setLanguages(List.of(new SoapLanguage("en", "English")));
        return result;
    }

    @Test
    void snapshotIsCachedAcrossCalls() {
        when(countryRepository.findAllByOrderByNameAscIsoCodeAsc())
                .thenReturn(List.of(entity(1L, "US", "United States")));

        assertThat(service.listCountries()).hasSize(1);
        assertThat(service.listCountries()).hasSize(1);

        verify(countryRepository, times(1)).findAllByOrderByNameAscIsoCodeAsc();
    }

    @Test
    void emptyStoreIsNotCached() {
        when(countryRepository.findAllByOrderByNameAscIsoCodeAsc()).thenReturn(List.of());

        assertThat(service.listCountries()).isEmpty();
        assertThat(service.listCountries()).isEmpty();

        verify(countryRepository, times(2)).findAllByOrderByNameAscIsoCodeAsc();
    }

    @Test
    void getCountryReturnsStoredCountryById() {
        when(countryRepository.findById(7L)).thenReturn(Optional.of(entity(7L, "US", "United States", "en", "English")));

        CountryInfoResponse found = service.getCountry(7L);

        assertThat(found.getId()).isEqualTo(7L);
        assertThat(found.getIsoCode()).isEqualTo("US");
        assertThat(found.getLanguages()).extracting(LanguageDto::getIsoCode).containsExactly("en");
        verifyNoInteractions(soapClient);
    }

    @Test
    void getCountryUnknownIdIsNotFound() {
        when(countryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCountry(999L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(soapClient);
    }

    @Test
    void updateCountryUpsertsWithImmutableIsoAndEvictsCache() {
        when(countryRepository.findAllByOrderByNameAscIsoCodeAsc()).thenReturn(List.of(entity(1L, "DE", "Germany")));
        service.listCountries();

        when(countryRepository.findById(2L)).thenReturn(Optional.of(entity(2L, "DE", "Deutschland", "de", "German")));

        CountryInfoResponse update = new CountryInfoResponse();
        update.setName("Germany");
        update.setCapitalCity("Berlin");
        update.setLanguages(List.of(new LanguageDto("de", "Deutsch")));

        CountryInfoResponse response = service.updateCountry(2L, update);

        assertThat(response.getId()).isEqualTo(2L);
        assertThat(response.getIsoCode()).isEqualTo("DE"); // ISO is immutable
        assertThat(response.getName()).isEqualTo("Germany");
        assertThat(response.getCapitalCity()).isEqualTo("Berlin");
        assertThat(response.getLanguages()).extracting(LanguageDto::getName).containsExactly("Deutsch");

        ArgumentCaptor<CountryInfoResponse> captor = ArgumentCaptor.forClass(CountryInfoResponse.class);
        verify(storeRepository, times(1)).upsert(captor.capture());
        assertThat(captor.getValue().getIsoCode()).isEqualTo("DE");

        // Cache was invalidated: the next list read goes to the store.
        service.listCountries();
        verify(countryRepository, times(2)).findAllByOrderByNameAscIsoCodeAsc();
    }

    @Test
    void updateCountryUnknownIdIsNotFound() {
        when(countryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCountry(999L, new CountryInfoResponse()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(storeRepository);
    }

    @Test
    void deleteCountrySoftDeletesAndEvictsCache() {
        when(countryRepository.findAllByOrderByNameAscIsoCodeAsc()).thenReturn(List.of(entity(1L, "AA", "Country A")));
        service.listCountries();

        when(countryRepository.softDeleteById(1L)).thenReturn(1);
        service.deleteCountry(1L);

        service.listCountries();
        verify(countryRepository, times(2)).findAllByOrderByNameAscIsoCodeAsc();
    }

    @Test
    void deleteCountryUnknownIdIsNotFound() {
        when(countryRepository.softDeleteById(999L)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteCountry(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void lookupByNameNeverTouchesTheStore() {
        when(soapClient.getCountryIsoCode("Kenya")).thenReturn("KE");
        when(soapClient.getCountryInfo("KE")).thenReturn(upstreamResult("KE", "Kenya"));

        CountryInfoResponse found = service.lookupByName("Kenya");

        assertThat(found.getIsoCode()).isEqualTo("KE");
        assertThat(found.getId()).isNull();
        verifyNoInteractions(countryRepository, storeRepository);
    }

    @Test
    void lookupByNameIsoResolutionFailureIsNotFound() {
        when(soapClient.getCountryIsoCode("Atlantis")).thenThrow(new RuntimeException("upstream down"));

        assertThatThrownBy(() -> service.lookupByName("Atlantis"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(countryRepository, storeRepository);
    }

    @Test
    void lookupByNameNullResultIsNotFound() {
        when(soapClient.getCountryIsoCode("Atlantis")).thenReturn("No country found by that name");
        when(soapClient.getCountryInfo("No country found by that name")).thenReturn(null);

        assertThatThrownBy(() -> service.lookupByName("Atlantis"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
