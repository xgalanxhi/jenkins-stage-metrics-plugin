package io.jenkins.plugins.sample;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Example of Jenkins global configuration.
 */
@Extension
public class StageMetricsConfiguration  extends GlobalConfiguration {

    private String endpointUrl;
    private String label;
    private boolean trustSelfSigned;
    private String lastError;
    private String username;
    private String password;


    @DataBoundSetter
    public void setUsername(String username) {
        this.username = username;
        save();
    }

    @DataBoundSetter
    public void setPassword(String password) {
        this.password = password;
        save();
    }

    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
        save(); // Save to disk so it persists across restarts
    }
    public boolean isTrustSelfSigned() {
        return trustSelfSigned;
    }

    @DataBoundSetter
    public void setTrustSelfSigned(boolean trustSelfSigned) {
        this.trustSelfSigned = trustSelfSigned;
        save();
    }
    public StageMetricsConfiguration() {
        load(); // Load saved configuration from disk
    }

    public static StageMetricsConfiguration get() {
        return GlobalConfiguration.all().get(StageMetricsConfiguration.class);
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    @DataBoundSetter
    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
        save();
    }

    public String getLabel() {
        return label;
    }

    @DataBoundSetter
    public void setLabel(String label) {
        this.label = label;
        save();
    }
}