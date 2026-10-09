package com.ncba.countriesinfo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class CountryInfoResponse {

    @JsonProperty("id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long id;

    @JsonProperty("country_iso_code")
    private String isoCode = "";

    @JsonProperty("country_name")
    private String name = "";

    @JsonProperty("capital_city")
    private String capitalCity = "";

    @JsonProperty("phone_code")
    private String phoneCode = "";

    @JsonProperty("continent_code")
    private String continentCode = "";

    @JsonProperty("currency_iso_code")
    private String currencyIsoCode = "";

    @JsonProperty("country_flag")
    private String countryFlag = "";

    @JsonProperty("languages")
    private List<LanguageDto> languages;

    public CountryInfoResponse() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIsoCode() { return isoCode; }
    public void setIsoCode(String isoCode) { this.isoCode = isoCode == null ? "" : isoCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }
    public String getCapitalCity() { return capitalCity; }
    public void setCapitalCity(String capitalCity) { this.capitalCity = capitalCity == null ? "" : capitalCity; }
    public String getPhoneCode() { return phoneCode; }
    public void setPhoneCode(String phoneCode) { this.phoneCode = phoneCode == null ? "" : phoneCode; }
    public String getContinentCode() { return continentCode; }
    public void setContinentCode(String continentCode) { this.continentCode = continentCode == null ? "" : continentCode; }
    public String getCurrencyIsoCode() { return currencyIsoCode; }
    public void setCurrencyIsoCode(String currencyIsoCode) { this.currencyIsoCode = currencyIsoCode == null ? "" : currencyIsoCode; }
    public String getCountryFlag() { return countryFlag; }
    public void setCountryFlag(String countryFlag) { this.countryFlag = countryFlag == null ? "" : countryFlag; }
    public List<LanguageDto> getLanguages() { return languages; }
    public void setLanguages(List<LanguageDto> languages) { this.languages = languages; }
}
