package com.ncba.countriesinfo.converter;

import com.ncba.countriesinfo.client.CountryInfoResult;
import com.ncba.countriesinfo.client.SoapLanguage;
import com.ncba.countriesinfo.dto.CountryInfoResponse;
import com.ncba.countriesinfo.dto.LanguageDto;
import com.ncba.countriesinfo.entity.CountryInfo;
import com.ncba.countriesinfo.entity.Language;

import java.util.Comparator;
import java.util.List;

/**
 * Maps between the persistence, DTO and upstream SOAP shapes.
 */
public final class CountryConverter {

    private CountryConverter() {
    }

    public static CountryInfoResponse toResponse(CountryInfo entity) {
        CountryInfoResponse dto = new CountryInfoResponse();
        dto.setId(entity.getId());
        dto.setIsoCode(entity.getIsoCode());
        dto.setName(entity.getName());
        dto.setCapitalCity(entity.getCapitalCity());
        dto.setPhoneCode(entity.getPhoneCode());
        dto.setContinentCode(entity.getContinentCode());
        dto.setCurrencyIsoCode(entity.getCurrencyIsoCode());
        dto.setCountryFlag(entity.getCountryFlag());
        dto.setLanguages(entity.getLanguages().stream()
                .sorted(Comparator.comparing(Language::getIsoCode))
                .map(lang -> new LanguageDto(lang.getIsoCode(), lang.getName()))
                .toList());
        return dto;
    }

    public static CountryInfoResponse toResponse(CountryInfoResult result) {
        CountryInfoResponse dto = new CountryInfoResponse();
        dto.setIsoCode(result.getIsoCode());
        dto.setName(result.getName());
        dto.setCapitalCity(result.getCapitalCity());
        dto.setPhoneCode(result.getPhoneCode());
        dto.setContinentCode(result.getContinentCode());
        dto.setCurrencyIsoCode(result.getCurrencyIsoCode());
        dto.setCountryFlag(result.getCountryFlag());
        dto.setLanguages(result.getLanguages().stream()
                .map((SoapLanguage lang) -> new LanguageDto(lang.getIsoCode(), lang.getName()))
                .toList());
        return dto;
    }

    public static List<CountryInfoResponse> toResponseList(List<CountryInfoResult> results) {
        return results.stream().map(CountryConverter::toResponse).toList();
    }
}
