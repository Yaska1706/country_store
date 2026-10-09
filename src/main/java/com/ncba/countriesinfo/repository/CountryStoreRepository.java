package com.ncba.countriesinfo.repository;

import com.ncba.countriesinfo.dto.CountryInfoResponse;
import com.ncba.countriesinfo.dto.LanguageDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class CountryStoreRepository {

    private final JdbcTemplate jdbcTemplate;

    public CountryStoreRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public enum SaveOutcome {
        INSERTED, UPDATED, SKIPPED
    }

    public record LanguageChanges(int added, int updated, int deleted) {

        public boolean unchanged() {
            return added == 0 && updated == 0 && deleted == 0;
        }
    }

    public record UpsertOutcome(SaveOutcome country, LanguageChanges languages) {
    }

    /**
     * Writes a country with its languages in one transaction. Soft-deleted
     * rows are left untouched: the sync never resurrects a deleted country.
     */
    @Transactional
    public UpsertOutcome upsert(CountryInfoResponse info) {
        if (isSoftDeleted(info.getIsoCode())) {
            return new UpsertOutcome(SaveOutcome.SKIPPED, new LanguageChanges(0, 0, 0));
        }
        SaveOutcome countryOutcome = upsertCountry(info);
        Long countryId = currentCountryId(info.getIsoCode());
        LanguageChanges languageChanges = reconcileLanguages(countryId, info);
        return new UpsertOutcome(countryOutcome, languageChanges);
    }

    /**
     * Hard-deletes soft-deleted countries whose {@code deleted_at} is older
     * than the retention period. Languages are removed by the foreign key
     * cascade and sync logs are cleaned up explicitly (they have no FK).
     *
     * @return the number of purged country rows
     */
    @Transactional
    public int purgeDeletedCountries(Duration retention) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retention));
        jdbcTemplate.update("""
                DELETE FROM sync_logs
                WHERE country_iso_code IN (
                    SELECT iso_code FROM country_infos
                    WHERE deleted_at IS NOT NULL AND deleted_at < ?
                )
                """, cutoff);
        return jdbcTemplate.update("""
                DELETE FROM country_infos
                WHERE deleted_at IS NOT NULL AND deleted_at < ?
                """, cutoff);
    }

    private boolean isSoftDeleted(String isoCode) {
        Boolean deleted = jdbcTemplate.query(
                "SELECT deleted_at IS NOT NULL FROM country_infos WHERE iso_code = ?",
                rs -> rs.next() && rs.getBoolean(1),
                isoCode);
        return Boolean.TRUE.equals(deleted);
    }

    /** Affected rows: 1 insert, 2 changed update, 0 unchanged. */
    private SaveOutcome upsertCountry(CountryInfoResponse info) {
        int affected = jdbcTemplate.update("""
                INSERT INTO country_infos (iso_code, name, capital_city, phone_code, continent_code, currency_iso_code, country_flag)
                VALUES (?, ?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE
                    name = new.name,
                    capital_city = new.capital_city,
                    phone_code = new.phone_code,
                    continent_code = new.continent_code,
                    currency_iso_code = new.currency_iso_code,
                    country_flag = new.country_flag
                """,
                info.getIsoCode(), info.getName(), info.getCapitalCity(), info.getPhoneCode(),
                info.getContinentCode(), info.getCurrencyIsoCode(), info.getCountryFlag());

        return switch (affected) {
            case 0 -> SaveOutcome.SKIPPED;
            case 1 -> SaveOutcome.INSERTED;
            default -> SaveOutcome.UPDATED;
        };
    }

    private Long currentCountryId(String isoCode) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM country_infos WHERE iso_code = ?", Long.class, isoCode);
    }

    private LanguageChanges reconcileLanguages(Long countryId, CountryInfoResponse info) {
        Map<String, String> stored = storedLanguages(countryId);

        Map<String, String> upstream = new LinkedHashMap<>();
        List<LanguageDto> languages = info.getLanguages() != null ? info.getLanguages() : List.of();
        for (LanguageDto language : languages) {
            upstream.put(language.getIsoCode(), language.getName());
        }

        int added = 0;
        int updated = 0;
        for (Map.Entry<String, String> entry : upstream.entrySet()) {
            String storedName = stored.get(entry.getKey());
            if (storedName != null) {
                if (!storedName.equals(entry.getValue())) {
                    updated++;
                }
            } else {
                added++;
            }
        }
        int deleted = 0;
        for (String isoCode : stored.keySet()) {
            if (!upstream.containsKey(isoCode)) {
                deleted++;
            }
        }
        if (added == 0 && updated == 0 && deleted == 0) {
            return new LanguageChanges(0, 0, 0);
        }

        jdbcTemplate.update("DELETE FROM languages WHERE country_id = ?", countryId);
        if (!upstream.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO languages (country_id, iso_code, name) VALUES (?, ?, ?)",
                    upstream.entrySet(),
                    upstream.size(),
                    (ps, entry) -> {
                        ps.setLong(1, countryId);
                        ps.setString(2, entry.getKey());
                        ps.setString(3, entry.getValue());
                    });
        }
        return new LanguageChanges(added, updated, deleted);
    }

    private Map<String, String> storedLanguages(Long countryId) {
        return jdbcTemplate.query(
                "SELECT iso_code, name FROM languages WHERE country_id = ?",
                rs -> {
                    Map<String, String> languages = new LinkedHashMap<>();
                    while (rs.next()) {
                        languages.put(rs.getString(1), rs.getString(2));
                    }
                    return languages;
                },
                countryId);
    }
}
