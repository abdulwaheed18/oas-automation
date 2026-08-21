package com.oastest.automation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oastest.automation.config.BrandingProperties;

/** Exposes branding placeholders so the UI can re-brand itself from configuration. */
@RestController
@RequestMapping("/api/branding")
public class BrandingController {

    private final BrandingProperties branding;

    public BrandingController(BrandingProperties branding) {
        this.branding = branding;
    }

    @GetMapping
    public BrandingProperties branding() {
        return branding;
    }
}
