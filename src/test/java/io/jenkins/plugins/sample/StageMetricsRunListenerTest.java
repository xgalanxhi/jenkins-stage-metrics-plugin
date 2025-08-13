package io.jenkins.plugins.sample;

import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

public class StageMetricsRunListenerTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void testBasicFunctionality() throws Exception {
        // Add basic tests for your plugin
        StageMetricsConfiguration config = StageMetricsConfiguration.get();
        config.setHttpUrl("http://test-endpoint.com");
        
        // Test configuration
        assert config.getHttpUrl().equals("http://test-endpoint.com");
    }
}
