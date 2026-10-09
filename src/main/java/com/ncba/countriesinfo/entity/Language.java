package com.ncba.countriesinfo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

@Entity
@Table(name = "languages")
@IdClass(LanguageId.class)
public class Language {

    @Id
    @Column(name = "iso_code", nullable = false, length = 10)
    private String isoCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "country_id", nullable = false)
    private CountryInfo countryInfo;

    public String getIsoCode() { return isoCode; }
    public void setIsoCode(String isoCode) { this.isoCode = isoCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public CountryInfo getCountryInfo() { return countryInfo; }
    public void setCountryInfo(CountryInfo countryInfo) { this.countryInfo = countryInfo; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Language that)) return false;
        return Objects.equals(isoCode, that.isoCode) &&
                Objects.equals(countryInfo, that.countryInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isoCode, countryInfo);
    }
}
