package com.ncba.countriesinfo.repository;

import com.ncba.countriesinfo.entity.SyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncLogRepository extends JpaRepository<SyncLog, Long> {

    @Modifying
    @Query("DELETE FROM SyncLog s WHERE s.countryIsoCode = :isoCode")
    void deleteByCountryIsoCode(@Param("isoCode") String isoCode);
}
