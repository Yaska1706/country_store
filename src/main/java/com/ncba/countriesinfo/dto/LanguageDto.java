package com.ncba.countriesinfo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class LanguageDto {

    @JsonProperty("iso_code")
    private String isoCode = "";

    @JsonProperty("name")
    private String name = "";

    public LanguageDto() {}

    public LanguageDto(String isoCode, String name) {
        this.isoCode = isoCode;
        this.name = name;
    }

    public String getIsoCode() { return isoCode; }
    public void setIsoCode(String isoCode) { this.isoCode = isoCode == null ? "" : isoCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name; }
}
