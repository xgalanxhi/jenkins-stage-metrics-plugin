package io.jenkins.plugins.sample;

import hudson.Extension;
import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * Example of Jenkins global configuration.
 */
@Extension
public class StageMetricsConfiguration extends GlobalConfiguration {

    private String endpointUrl;
    private String username;
    private String password;
    private boolean trustSelfSigned;
    private String controllerName;
    private String lastError = "";

    public StageMetricsConfiguration() {
        load();
    }

    public static StageMetricsConfiguration get() {
        return GlobalConfiguration.all().get(StageMetricsConfiguration.class);
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
        req.bindJSON(this, formData);
        save();
        return super.configure(req, formData);
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    @DataBoundSetter
    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public String getUsername() {
        return username;
    }

    @DataBoundSetter
    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    @DataBoundSetter
    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isTrustSelfSigned() {
        return trustSelfSigned;
    }

    @DataBoundSetter
    public void setTrustSelfSigned(boolean trustSelfSigned) {
        this.trustSelfSigned = trustSelfSigned;
    }

    public String getControllerName() {
        return controllerName;
    }

    @DataBoundSetter
    public void setControllerName(String controllerName) {
        this.controllerName = controllerName;
    }

    public String getLastError() {
        return lastError != null ? lastError : "";
    }

    public void setLastError(String lastError) {
        this.lastError = lastError != null ? lastError : "";
        save();
    }

    public void clearLastError() {
        this.lastError = "";
        save();
    }

    public void appendToLastError(String message) {
        if (this.lastError == null) {
            this.lastError = "";
        }
        this.lastError += message + "\n";
        save();
    }

    public FormValidation doCheckEndpointUrl(@QueryParameter String value)
            throws IOException, ServletException {
        if (value.length() == 0)
            return FormValidation.error("Please set an endpoint URL");
        if (!value.startsWith("http://") && !value.startsWith("https://"))
            return FormValidation.warning("URL should start with http:// or https://");
        return FormValidation.ok();
    }

    public FormValidation doCheckUsername(@QueryParameter String value)
            throws IOException, ServletException {
        if (value.length() == 0)
            return FormValidation.error("Please set a username");
        return FormValidation.ok();
    }

    public FormValidation doCheckPassword(@QueryParameter String value)
            throws IOException, ServletException {
        if (value.length() == 0)
            return FormValidation.error("Please set a password");
        return FormValidation.ok();
    }

    public FormValidation doCheckControllerName(@QueryParameter String value)
            throws IOException, ServletException {
        if (value == null || value.trim().isEmpty())
            return FormValidation.warning("Controller name is optional but recommended for identifying different Jenkins instances");
        return FormValidation.ok();
    }
}