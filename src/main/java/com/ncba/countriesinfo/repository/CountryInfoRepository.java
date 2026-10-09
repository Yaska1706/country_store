package com.ncba.countriesinfo.repository;

import com.ncba.countriesinfo.entity.CountryInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CountryInfoRepository extends JpaRepository<CountryInfo, Long> {
    List<CountryInfo> findAllByOrderByNameAscIsoCodeAsc();

    @Modifying
    @Query("UPDATE CountryInfo c SET c.deletedAt = CURRENT_TIMESTAMP WHERE c.id = :id AND c.deletedAt IS NULL")
    int softDeleteById(@Param("id") Long id);
}
