package com.oastest.automation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Branding / white-label placeholders. Change these in {@code application.properties} (or via
 * environment variables) to re-brand the tool for a specific company without touching code.
 *
 * <pre>
 *   oas.branding.app-name=ACME OAS Validator
 *   oas.branding.company=ACME Corp
 * </pre>
 */
@ConfigurationProperties(prefix = "oas.branding")
public class BrandingProperties {

    /** Product/application name shown in the UI header and browser title. */
    private String appName = "OAS Automation Test Suite";

    /** Owning company / team, shown in the footer and report. */
    private String company = "Generic";

    /** Short tagline shown under the app name. */
    private String tagline = "Validate that your gateway enforces the OpenAPI contract";

    /** Primary accent colour (any valid CSS colour) used across the UI. */
    private String primaryColor = "#4f46e5";

    /** Support / owner contact shown in the footer. Optional. */
    private String supportContact = "";

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public void setPrimaryColor(String primaryColor) {
        this.primaryColor = primaryColor;
    }

    public String getSupportContact() {
        return supportContact;
    }

    public void setSupportContact(String supportContact) {
        this.supportContact = supportContact;
    }
}
