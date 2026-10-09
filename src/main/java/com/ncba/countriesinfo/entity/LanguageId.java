package com.ncba.countriesinfo.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite ID for {@link Language}, used with {@code @IdClass}.
 * Field names and types must match the {@code @Id} properties of {@code Language}.
 */
public class LanguageId implements Serializable {

    private String isoCode;
    private CountryInfo countryInfo;

    public LanguageId() {
    }

    public LanguageId(String isoCode, CountryInfo countryInfo) {
        this.isoCode = isoCode;
        this.countryInfo = countryInfo;
    }

    public String getIsoCode() {
        return isoCode;
    }

    public void setIsoCode(String isoCode) {
        this.isoCode = isoCode;
    }

    public CountryInfo getCountryInfo() {
        return countryInfo;
    }

    public void setCountryInfo(CountryInfo countryInfo) {
        this.countryInfo = countryInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LanguageId that)) return false;
        return Objects.equals(isoCode, that.isoCode) &&
                Objects.equals(countryInfo, that.countryInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isoCode, countryInfo);
    }
}
