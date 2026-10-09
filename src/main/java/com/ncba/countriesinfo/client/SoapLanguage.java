package com.ncba.countriesinfo.client;

public class SoapLanguage {
    private String isoCode;
    private String name;

    public SoapLanguage() {}

    public SoapLanguage(String isoCode, String name) {
        this.isoCode = isoCode;
        this.name = name;
    }

    public String getIsoCode() { return isoCode; }
    public void setIsoCode(String isoCode) { this.isoCode = isoCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
