package com.ncba.countriesinfo.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A stored country. Rows are soft-deleted by setting {@code deletedAt}; all
 * Hibernate queries automatically filter soft-deleted rows via
 * {@code @SQLRestriction}. A background vacuum hard-deletes rows whose
 * {@code deletedAt} is older than the retention period.
 */
@Entity
@Table(name = "country_infos")
@SQLRestriction("deleted_at IS NULL")
public class CountryInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "iso_code", nullable = false, length = 2, columnDefinition = "char(2)", unique = true)
    private String isoCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "capital_city", nullable = false)
    private String capitalCity = "";

    @Column(name = "phone_code", nullable = false, length = 32)
    private String phoneCode = "";

    @Column(name = "continent_code", nullable = false, length = 2, columnDefinition = "char(2)")
    private String continentCode = "";

    @Column(name = "currency_iso_code", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currencyIsoCode = "";

    @Column(name = "country_flag", nullable = false, length = 512)
    private String countryFlag = "";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at", columnDefinition = "timestamp NULL DEFAULT NULL")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "countryInfo", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Language> languages = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public List<Language> getLanguages() { return languages; }
    public void setLanguages(List<Language> languages) { this.languages = languages; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CountryInfo that)) return false;
        return Objects.equals(isoCode, that.isoCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isoCode);
    }
}
