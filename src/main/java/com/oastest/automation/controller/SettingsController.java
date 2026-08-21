package com.oastest.automation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oastest.automation.config.TestingProperties;

/**
 * Exposes the default expected-status configuration so the UI can pre-fill (and let the user
 * override) the accepted codes per run.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final TestingProperties testing;

    public SettingsController(TestingProperties testing) {
        this.testing = testing;
    }

    @GetMapping
    public TestingProperties settings() {
        return testing;
    }
}
