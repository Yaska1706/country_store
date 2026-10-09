package com.ncba.countriesinfo.client;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoapClientTest {

    private static final String US_RESULT = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <m:FullCountryInfoResponse xmlns:m="http://www.oorsprong.org/websamples.countryinfo">
                  <m:FullCountryInfoResult>
                    <m:sISOCode>US</m:sISOCode>
                    <m:sName>United States</m:sName>
                    <m:sCapitalCity>Washington</m:sCapitalCity>
                    <m:Languages>
                      <m:tLanguage>
                        <m:sISOCode>eng</m:sISOCode>
                        <m:sName>English</m:sName>
                      </m:tLanguage>
                    </m:Languages>
                  </m:FullCountryInfoResult>
                </m:FullCountryInfoResponse>
              </soap:Body>
            </soap:Envelope>
            """;

    private HttpServer server;
    private SimpleMeterRegistry meterRegistry;
    private SoapClient client;

    @BeforeEach
    void setUp() throws IOException {
        meterRegistry = new SimpleMeterRegistry();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/wso";

        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(3)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(20), 2.0))
                .retryExceptions(
                        HttpServerErrorException.class,
                        HttpClientErrorException.TooManyRequests.class,
                        ResourceAccessException.class)
                .build();

        client = new SoapClient(new RestTemplate(), baseUrl,
                CircuitBreakerRegistry.of(breakerConfig),
                RetryRegistry.of(retryConfig),
                meterRegistry);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(int status, String body) {
        server.createContext("/wso", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private Counter upstreamResult(String operation, String result) {
        return meterRegistry.find("upstream.requests")
                .tag("operation", operation)
                .tag("result", result)
                .counter();
    }

    @Test
    void retriesOnServerErrorThenSucceeds() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/wso", exchange -> {
            byte[] bytes;
            if (requests.incrementAndGet() < 3) {
                bytes = "boom".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, bytes.length);
            } else {
                bytes = US_RESULT.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        CountryInfoResult result = client.getCountryInfo("US");

        assertThat(result.getName()).isEqualTo("United States");
        assertThat(result.getLanguages()).hasSize(1);
        assertThat(requests.get()).isEqualTo(3);
        assertThat(upstreamResult("country_info", "ok")).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1));
        assertThat(upstreamResult("country_info", "failed")).isNull();
    }

    @Test
    void circuitOpensAfterThreeFullFailures() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/wso", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.getCountryInfo("US"))
                    .isInstanceOf(RuntimeException.class);
        }

        assertThatThrownBy(() -> client.getCountryInfo("US"))
                .isInstanceOf(CallNotPermittedException.class);

        assertThat(requests.get()).isEqualTo(9);
        assertThat(upstreamResult("country_info", "failed")).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(3));
        assertThat(upstreamResult("country_info", "circuit_open")).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1));
    }

    @Test
    void terminal4xxIsNotRetried() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/wso", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = "not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        assertThatThrownBy(() -> client.getCountryInfo("US"))
                .isInstanceOf(SoapClient.UpstreamHttpException.class);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(upstreamResult("country_info", "error")).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1));
        assertThat(upstreamResult("country_info", "failed")).isNull();
    }

    @Test
    void missingResultElementReturnsNull() {
        respond(200, """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <m:FullCountryInfoResponse xmlns:m="http://www.oorsprong.org/websamples.countryinfo"/>
                  </soap:Body>
                </soap:Envelope>
                """);

        assertThat(client.getCountryInfo("XX")).isNull();
        assertThat(upstreamResult("country_info", "ok")).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1));
    }

    @Test
    void parsesAllCountriesResponse() {
        respond(200, """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <m:FullCountryInfoAllCountriesResponse xmlns:m="http://www.oorsprong.org/websamples.countryinfo">
                      <m:FullCountryInfoAllCountriesResult>
                        <m:tCountryInfo>
                          <m:sISOCode>AD</m:sISOCode>
                          <m:sName>Andorra</m:sName>
                          <m:Languages>
                            <m:tLanguage>
                              <m:sISOCode>cat</m:sISOCode>
                              <m:sName>Catalan</m:sName>
                            </m:tLanguage>
                          </m:Languages>
                        </m:tCountryInfo>
                      </m:FullCountryInfoAllCountriesResult>
                    </m:FullCountryInfoAllCountriesResponse>
                  </soap:Body>
                </soap:Envelope>
                """);

        List<CountryInfoResult> countries = client.getAllCountriesInfo();

        assertThat(countries).hasSize(1);
        assertThat(countries.get(0).getIsoCode()).isEqualTo("AD");
        assertThat(countries.get(0).getLanguages()).extracting(SoapLanguage::getName).containsExactly("Catalan");
        assertThat(upstreamResult("all_countries", "ok")).isNotNull().satisfies(c -> assertThat(c.count()).isEqualTo(1));
    }
}
