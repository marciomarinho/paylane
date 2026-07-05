package com.paylane.paymentreactive.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

import java.net.URI;

@Configuration
public class AwsConfig {

    @Value("${aws.region:ap-southeast-2}")
    private String region;

    @Value("${aws.endpoint:}")
    private String endpoint;

    /** Async SNS client so publishing never blocks the event loop. */
    @Bean
    public SnsAsyncClient snsAsyncClient() {
        var builder = SnsAsyncClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }
}
