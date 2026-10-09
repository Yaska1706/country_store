package com.ncba.countriesinfo.client;

import java.util.List;

public class CountryInfoResult {
    private String isoCode;
    private String name;
    private String capitalCity;
    private String phoneCode;
    private String continentCode;
    private String currencyIsoCode;
    private String countryFlag;
    private List<SoapLanguage> languages;

    public CountryInfoResult() {}

    public CountryInfoResult(String isoCode, String name, String capitalCity, String phoneCode, String continentCode, String currencyIsoCode, String countryFlag, List<SoapLanguage> languages) {
        this.isoCode = isoCode;
        this.name = name;
        this.capitalCity = capitalCity;
        this.phoneCode = phoneCode;
        this.continentCode = continentCode;
        this.currencyIsoCode = currencyIsoCode;
        this.countryFlag = countryFlag;
        this.languages = languages;
    }

    public String getIsoCode() { return isoCode; }
    public void setIsoCode(String isoCode) { this.isoCode = isoCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCapitalCity() { return capitalCity; }
    public void setCapitalCity(String capitalCity) { this.capitalCity = capitalCity; }
    public String getPhoneCode() { return phoneCode; }
    public void setPhoneCode(String phoneCode) { this.phoneCode = phoneCode; }
    public String getContinentCode() { return continentCode; }
    public void setContinentCode(String continentCode) { this.continentCode = continentCode; }
    public String getCurrencyIsoCode() { return currencyIsoCode; }
    public void setCurrencyIsoCode(String currencyIsoCode) { this.currencyIsoCode = currencyIsoCode; }
    public String getCountryFlag() { return countryFlag; }
    public void setCountryFlag(String countryFlag) { this.countryFlag = countryFlag; }
    public List<SoapLanguage> getLanguages() { return languages; }
    public void setLanguages(List<SoapLanguage> languages) { this.languages = languages; }
}
