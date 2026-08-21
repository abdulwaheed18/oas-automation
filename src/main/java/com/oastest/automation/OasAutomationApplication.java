package com.oastest.automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.oastest.automation.config.BrandingProperties;

/**
 * Entry point for the OAS Automation Test Suite.
 *
 * <p>A single self-contained Spring Boot application that bundles both the REST backend and the
 * wizard UI (served as static resources). It parses an OpenAPI spec, generates negative/edge-case
 * test cases (one negative mutation per case), executes them against a target gateway URL and
 * reports whether the gateway enforces the contract as declared in the spec.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(BrandingProperties.class)
public class OasAutomationApplication {

    public static void main(String[] args) {
        SpringApplication.run(OasAutomationApplication.class, args);
    }
}
