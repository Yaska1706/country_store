package com.ncba.countriesinfo.service;

import com.ncba.countriesinfo.client.SoapClient;
import com.ncba.countriesinfo.converter.CountryConverter;
import com.ncba.countriesinfo.dto.CountryInfoResponse;
import com.ncba.countriesinfo.entity.CountryInfo;
import com.ncba.countriesinfo.exception.ResourceNotFoundException;
import com.ncba.countriesinfo.repository.CountryInfoRepository;
import com.ncba.countriesinfo.repository.CountryStoreRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CountryService {

    private static final Logger log = LoggerFactory.getLogger(CountryService.class);

    private static final String SNAPSHOT_KEY = "snapshot";

    private final CountryInfoRepository countryRepository;
    private final CountryStoreRepository storeRepository;
    private final SoapClient soapClient;
    private final Cache countriesCache;
    private final EntityManager entityManager;

    public CountryService(CountryInfoRepository countryRepository,
                          CountryStoreRepository storeRepository,
                          SoapClient soapClient,
                          CacheManager cacheManager,
                          EntityManager entityManager) {
        this.countryRepository = countryRepository;
        this.storeRepository = storeRepository;
        this.soapClient = soapClient;
        this.countriesCache = cacheManager.getCache("countries");
        this.entityManager = entityManager;
    }

    public void ping() {
        entityManager.createNativeQuery("SELECT 1").getSingleResult();
    }

    public List<CountryInfoResponse> listCountries() {
        return snapshot();
    }

    /**
     * Reads one country by id. Soft-deleted countries are invisible (the
     * repository filters them out), so they report as not found.
     */
    public CountryInfoResponse getCountry(Long id) {
        return countryRepository.findById(id)
                .map(CountryConverter::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("country not found"));
    }

    public CountryInfoResponse lookupByName(String countryName) {
        String isoCode;
        try {
            isoCode = soapClient.getCountryIsoCode(countryName);
        } catch (Exception e) {
            log.warn("Failed to resolve country ISO: country_name={}", countryName, e);
            throw new ResourceNotFoundException("country not found");
        }

        var result = soapClient.getCountryInfo(isoCode);
        if (result == null) {
            throw new ResourceNotFoundException("country not found");
        }
        return CountryConverter.toResponse(result);
    }

    @Transactional
    public CountryInfoResponse updateCountry(Long id, CountryInfoResponse dto) {
        CountryInfo existing = countryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("country not found"));

        CountryInfoResponse info = new CountryInfoResponse();
        info.setId(id);
        info.setIsoCode(existing.getIsoCode());
        info.setName(dto.getName());
        info.setCapitalCity(dto.getCapitalCity());
        info.setPhoneCode(dto.getPhoneCode());
        info.setContinentCode(dto.getContinentCode());
        info.setCurrencyIsoCode(dto.getCurrencyIsoCode());
        info.setCountryFlag(dto.getCountryFlag());
        info.setLanguages(dto.getLanguages());

        storeRepository.upsert(info);
        evictSnapshot();
        return info;
    }

    @Transactional
    public void deleteCountry(Long id) {
        int deleted = countryRepository.softDeleteById(id);
        if (deleted == 0) {
            throw new ResourceNotFoundException("country not found");
        }
        evictSnapshot();
    }

    private List<CountryInfoResponse> snapshot() {
        List<CountryInfoResponse> cached = getCachedSnapshot();
        if (cached != null) {
            return cached;
        }

        List<CountryInfoResponse> countries = countryRepository.findAllByOrderByNameAscIsoCodeAsc().stream()
                .map(CountryConverter::toResponse)
                .toList();

        // A failed or empty read is not cached
        if (!countries.isEmpty()) {
            countriesCache.put(SNAPSHOT_KEY, countries);
        }
        return countries;
    }

    @SuppressWarnings("unchecked")
    private List<CountryInfoResponse> getCachedSnapshot() {
        Cache.ValueWrapper wrapper = countriesCache.get(SNAPSHOT_KEY);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        return value instanceof List<?> list ? (List<CountryInfoResponse>) list : null;
    }

    private void evictSnapshot() {
        countriesCache.evict(SNAPSHOT_KEY);
    }
}
