package com.ncba.countriesinfo.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Retries and the circuit breaker : 3 attempts with exponential backoff
 * and jitter, breaker opens after 3 consecutive full failures and cools down
 * for 30 seconds. Every attempt is classified into the
 * {@code upstream_requests_total} metric.
 */
@Component
public class SoapClient {

    public static final String OPERATION_COUNTRY_ISO = "country_iso";
    public static final String OPERATION_COUNTRY_INFO = "country_info";
    public static final String OPERATION_ALL_COUNTRIES = "all_countries";

    private static final String INSTANCE = "soapService";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final MeterRegistry meterRegistry;

    public SoapClient(RestTemplate restTemplate,
                      @Value("${app.soap.base-url}") String baseUrl,
                      CircuitBreakerRegistry circuitBreakerRegistry,
                      RetryRegistry retryRegistry,
                      MeterRegistry meterRegistry) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.meterRegistry = meterRegistry;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE);
        this.retry = retryRegistry.retry(INSTANCE);
        Gauge.builder("upstream.circuit.breaker.state", circuitBreaker,
                        breaker -> breaker.getState() == CircuitBreaker.State.OPEN ? 1 : 0)
                .description("1 while the upstream circuit breaker is open.")
                .register(meterRegistry);
    }

    public CountryInfoResult getCountryInfo(String isoCode) {
        String responseXml = send(OPERATION_COUNTRY_INFO, fullCountryInfoPayload(isoCode));
        Document doc = parseXml(responseXml);
        NodeList results = getElements(doc.getDocumentElement(), "FullCountryInfoResult");
        if (results.getLength() == 0) {
            return null;
        }
        return parseCountryInfoResult((Element) results.item(0));
    }

    public String getCountryIsoCode(String countryName) {
        String responseXml = send(OPERATION_COUNTRY_ISO, countryIsoCodePayload(countryName));
        Document doc = parseXml(responseXml);
        NodeList results = getElements(doc.getDocumentElement(), "CountryISOCodeResult");
        if (results.getLength() == 0) {
            return "";
        }
        return results.item(0).getTextContent();
    }

    public List<CountryInfoResult> getAllCountriesInfo() {
        String responseXml = send(OPERATION_ALL_COUNTRIES, fullCountryInfoAllCountriesPayload());
        Document doc = parseXml(responseXml);
        List<CountryInfoResult> countries = new ArrayList<>();
        NodeList tCountryInfoNodes = getElements(doc.getDocumentElement(), "tCountryInfo");
        for (int i = 0; i < tCountryInfoNodes.getLength(); i++) {
            countries.add(parseCountryInfoResult((Element) tCountryInfoNodes.item(i)));
        }
        return countries;
    }

    /**
     * Sends one SOAP request through the retry/breaker policy, 
     *  classifying as {@code ok}, {@code error}
     * (terminal 4xx), {@code failed} (attempts exhausted), {@code
     * circuit_open}.
     */
    private String send(String operation, String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("text/xml;charset=UTF-8"));
        HttpEntity<String> request = new HttpEntity<>(payload, headers);

        Supplier<String> attempt = () -> {
            ResponseEntity<String> response;
            try {
                response = restTemplate.postForEntity(baseUrl, request, String.class);
            } catch (HttpClientErrorException e) {
                // 429 is retried; other 4xx are terminal and reported as "error".
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    throw e;
                }
                record(operation, "error");
                throw new UpstreamHttpException(e.getStatusCode(), e);
            }
            record(operation, "ok");
            return response.getBody();
        };

        try {
            Callable<String> retried = Retry.decorateCallable(retry, attempt::get);
            return circuitBreaker.executeCallable(retried);
        } catch (CallNotPermittedException e) {
            record(operation, "circuit_open");
            throw e;
        } catch (UpstreamHttpException e) {
            throw e;
        } catch (Exception e) {
            record(operation, "failed");
            throw new RuntimeException("Upstream SOAP call failed after retries", e);
        }
    }

    private void record(String operation, String result) {
        meterRegistry.counter("upstream.requests", "operation", operation, "result", result).increment();
    }

    private String fullCountryInfoPayload(String isoCode) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <web:FullCountryInfo>
                      <web:sCountryISOCode>%s</web:sCountryISOCode>
                    </web:FullCountryInfo>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(isoCode);
    }

    private String countryIsoCodePayload(String countryName) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <web:CountryISOCode>
                      <web:sCountryName>%s</web:sCountryName>
                    </web:CountryISOCode>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(countryName);
    }

    private String fullCountryInfoAllCountriesPayload() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:web="http://www.oorsprong.org/websamples.countryinfo">
                  <soapenv:Header/>
                  <soapenv:Body>
                    <web:FullCountryInfoAllCountries/>
                  </soapenv:Body>
                </soapenv:Envelope>
                """;
    }

    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Disable XXE: this parser only ever reads a trusted upstream response.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse SOAP response", e);
        }
    }

    private CountryInfoResult parseCountryInfoResult(Element element) {
        CountryInfoResult result = new CountryInfoResult();
        result.setIsoCode(getTagValue(element, "sISOCode"));
        result.setName(getTagValue(element, "sName"));
        result.setCapitalCity(getTagValue(element, "sCapitalCity"));
        result.setPhoneCode(getTagValue(element, "sPhoneCode"));
        result.setContinentCode(getTagValue(element, "sContinentCode"));
        result.setCurrencyIsoCode(getTagValue(element, "sCurrencyISOCode"));
        result.setCountryFlag(getTagValue(element, "sCountryFlag"));

        List<SoapLanguage> languages = new ArrayList<>();
        NodeList tLanguageNodes = getElements(element, "tLanguage");
        for (int i = 0; i < tLanguageNodes.getLength(); i++) {
            Element langElem = (Element) tLanguageNodes.item(i);
            SoapLanguage lang = new SoapLanguage();
            lang.setIsoCode(getTagValue(langElem, "sISOCode"));
            lang.setName(getTagValue(langElem, "sName"));
            languages.add(lang);
        }
        result.setLanguages(languages);
        return result;
    }

    private String getTagValue(Element parent, String tagName) {
        NodeList list = getElements(parent, tagName);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent();
        }
        return "";
    }

    private NodeList getElements(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagNameNS("*", tagName);
        if (list.getLength() == 0) {
            list = parent.getElementsByTagName(tagName);
        }
        return list;
    }

    /**
     * A terminal upstream HTTP error (4xx other than 429). Not retried and
     * not counted as a breaker failure
     */
    public static class UpstreamHttpException extends RuntimeException {

        private final HttpStatusCode statusCode;

        public UpstreamHttpException(HttpStatusCode statusCode, Throwable cause) {
            super("upstream returned status " + statusCode.value(), cause);
            this.statusCode = statusCode;
        }

        public HttpStatusCode getStatusCode() {
            return statusCode;
        }
    }
}
