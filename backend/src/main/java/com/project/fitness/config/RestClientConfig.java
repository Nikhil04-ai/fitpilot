package com.project.fitness.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Registers RestClient.Builder as a bean, pre-configured with timeouts.
 *
 * Spring Boot 4.1 doesn't auto-register the HTTP client builders for injection — the
 * same thing that bit us with WebClient.Builder and ObjectMapper (AGENT.md §7) — so
 * it's declared explicitly here.
 *
 * This replaced WebClientConfig. The only outbound HTTP call the app makes is to
 * Gemini, and WebClient meant carrying spring-boot-starter-webflux and the whole
 * reactor-netty stack — a second web framework, its event-loop threads and ~10MB of
 * jars — purely to make one request that was immediately .block()ed anyway.
 * RestClient lives in spring-web, which is already on the classpath.
 *
 * Transport concerns (timeouts, which client implementation) live here rather than in
 * the service so the service can be tested against MockRestServiceServer, which works
 * by installing its own request factory on the builder.
 */
@Configuration
public class RestClientConfig {

    /**
     * Named for its only current caller. Applies to every outbound call made through
     * the shared builder.
     */
    @Value("${gemini.api.timeout-seconds:20}")
    private int timeoutSeconds;

    @Bean
    public RestClient.Builder restClientBuilder() {
        // Connect and read are capped separately. Without a timeout a hung or very slow
        // upstream holds a Tomcat worker open for as long as the connection stays
        // alive — one stalled AI call taking a request thread out of circulation
        // indefinitely.
        //
        // SimpleClientHttpRequestFactory (HttpURLConnection) is chosen explicitly
        // rather than left to auto-detection: with no Apache or Jetty client on the
        // classpath, Spring would select the JDK HttpClient, which builds an NIO
        // selector eagerly and therefore fails at startup on machines where that is
        // blocked. This app makes one outbound call, occasionally — connection pooling
        // buys nothing here, and starting reliably everywhere does.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds / 2)));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));

        return RestClient.builder().requestFactory(requestFactory);
    }
}
