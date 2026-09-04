package xyz.arryan.livia.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties({
        ApodClientProperties.class,
        NewsClientProperties.class,
        ArticlesClientProperties.class
})
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
