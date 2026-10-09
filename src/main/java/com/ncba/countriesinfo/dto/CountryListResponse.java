package com.ncba.countriesinfo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class CountryListResponse {

    @JsonProperty("countries")
    private List<CountryInfoResponse> countries;

    @JsonProperty("page")
    private int page;

    @JsonProperty("limit")
    private int limit;

    @JsonProperty("total")
    private int total;

    @JsonProperty("total_pages")
    private int totalPages;

    public CountryListResponse() {}

    public CountryListResponse(List<CountryInfoResponse> countries, int page, int limit, int total, int totalPages) {
        this.countries = countries;
        this.page = page;
        this.limit = limit;
        this.total = total;
        this.totalPages = totalPages;
    }

    public List<CountryInfoResponse> getCountries() { return countries; }
    public void setCountries(List<CountryInfoResponse> countries) { this.countries = countries; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
}
