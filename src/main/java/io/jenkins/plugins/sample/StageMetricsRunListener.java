package io.jenkins.plugins.sample;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.*;
import hudson.model.listeners.RunListener;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@Extension
public class StageMetricsRunListener extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(StageMetricsRunListener.class.getName());

    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        if (!(run instanceof WorkflowRun)) {
            return;
        }
        StageMetricsConfiguration config = StageMetricsConfiguration.get();
        WorkflowRun workflowRun = (WorkflowRun) run;
        FlowExecution execution = workflowRun.getExecution();
        if (execution == null) return;

        try {
            List<Map<String, Object>> stageData = new ArrayList<>();
            collectStageMetrics(execution, stageData);
            ParametersAction parameters = run.getAction(ParametersAction.class);
            EnvVars env = run.getEnvironment(listener);
            String jobUrl = env.get("JOB_URL");
            for (Action a : run.getAllActions()) {
                LOGGER.info("Action class: " + a.getClass().getName());
            }
            String buildTool = "unknown";
            if (parameters != null) {
                ParameterValue value = parameters.getParameter("BUILD_TOOL");
                if (value != null && value.getValue() != null) {
                    buildTool = value.getValue().toString();
                }
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("runId", run.getId());
            payload.put("jobName", run.getParent().getFullName());
            payload.put("jobUrl", jobUrl != null ? jobUrl : "unknown");
            payload.put("buildTool", buildTool);
            Map<String, Map<String, String>> stageEnvVars = extractStageEnvVarsFromLogs(run);

            for (Map<String, Object> stage : stageData) {
                Map<String, Object> stagePayload = new HashMap<>(payload); // clone the pipeline context
                String stageName = (String) stage.get("name");

                // Add env vars for this stage (if any)
                if (stageEnvVars.containsKey(stageName)) {
                    Map<String, String> envVars = stageEnvVars.get(stageName);
                    //stage.put("env", envVars);

                    // If this stage has a specific build tool env var, include it in payload
                    if (envVars.containsKey("BUILD_TOOL")) {
                        stagePayload.put("stageBuildTool", envVars.get("BUILD_TOOL"));
                    } else {
                        stagePayload.put("stageBuildTool", "unknown1");
                    }
                } else {
                    stagePayload.put("stageBuildTool", "unknown2");
                }

                stagePayload.putAll(stage); // flatten stage info into the payload
                sendMetrics(stagePayload); // send one request per stage
            }
        } catch (Exception e) {
            config.setLastError("Failed to send stage metrics: " + e.getMessage());
            listener.getLogger().println("Failed to send stage metrics: " + e.getMessage());
        }
    }

    private void collectStageMetrics(FlowExecution execution, List<Map<String, Object>> stages) throws Exception {
        DepthFirstScanner scanner = new DepthFirstScanner();
        List<FlowNode> allNodes = scanner.allNodes(execution);

        Map<String, FlowNode> stageStartNodes = new HashMap<>();

        for (FlowNode node : allNodes) {
            LabelAction label = node.getAction(LabelAction.class);
            if (label != null) {
                stageStartNodes.put(node.getId(), node);
            }
        }

        for (FlowNode startNode : stageStartNodes.values()) {
            FlowNode endNode = null;
            for (FlowNode node : allNodes) {
                if (node instanceof BlockEndNode && ((BlockEndNode) node).getStartNode().equals(startNode)) {
                    endNode = node;
                    break;
                }
            }

            long startTime = TimingAction.getStartTime(startNode);
            long duration = (endNode != null)
                    ? TimingAction.getStartTime(endNode) - startTime
                    : 0;

            Map<String, Object> stage = new HashMap<>();
            stage.put("name", startNode.getDisplayName());
            stage.put("startTimeMillis", startTime);
            stage.put("durationMillis", duration);

            // Collect sh step labels within this stage
            List<String> shLabels = new ArrayList<>();
            for (FlowNode node : allNodes) {
                // Only consider nodes within the stage block
                if (isDescendantOf(node, startNode, endNode)) {
                    if (node instanceof StepStartNode) {
                        StepStartNode stepNode = (StepStartNode) node;
                        String stepType = stepNode.getDisplayFunctionName();
                        if ("sh".equals(stepType)) {
                            // Get ArgumentsAction and extract 'label'
                            org.jenkinsci.plugins.workflow.actions.ArgumentsAction argsAction = stepNode.getAction(org.jenkinsci.plugins.workflow.actions.ArgumentsAction.class);
                            if (argsAction != null) {
                                Map<String, Object> args = argsAction.getArguments();
                                if (args != null && args.containsKey("label")) {
                                    Object labelArg = args.get("label");
                                    if (labelArg != null) {
                                        shLabels.add(labelArg.toString());
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!shLabels.isEmpty()) {
                stage.put("shLabels", shLabels);
                // Set the first sh label as the stageBuildTool
                stage.put("stageBuildTool", shLabels.get(0));
            }
            stages.add(stage);
        }
    }

    // Helper to check if a node is within the stage block
    private boolean isDescendantOf(FlowNode node, FlowNode startNode, FlowNode endNode) {
        if (startNode == null || node == null) return false;
        // If endNode is null, treat all nodes after startNode as in stage
        if (endNode == null) {
            return node.getId().compareTo(startNode.getId()) > 0;
        }
        return node.getId().compareTo(startNode.getId()) > 0 && node.getId().compareTo(endNode.getId()) < 0;
    }

    private void sendMetrics(Map<String, Object> payloadData) throws Exception {
        StageMetricsConfiguration config = StageMetricsConfiguration.get();
        String baseUrl = config.getEndpointUrl();
        String user = config.getUsername();
        String pass = config.getPassword();

        if (baseUrl == null || baseUrl.isEmpty()) {
            System.err.println("StageMetricsPlugin: No endpoint URL configured.");
            return;
        }

        // Convert payload map to JSON
        ObjectMapper mapper = new ObjectMapper();
        String payloadJson = mapper.writeValueAsString(payloadData);

        // Encode the JSON payload to be used in query param
        String encodedPayload = URLEncoder.encode(payloadJson, StandardCharsets.UTF_8);

        String fullUrl = baseUrl + "/rest/v1.0/objects?request=sendReportingData&payload=" + encodedPayload + "&reportObjectTypeName=ci_metrics";

        if (config.isTrustSelfSigned()) {
            trustAllCertificates();
        }

        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        String encoded = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + encoded);
        if (conn instanceof HttpsURLConnection && config.isTrustSelfSigned()) {
            ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
        }

        conn.setRequestMethod("POST");
        conn.setRequestProperty("accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 201) {
            config.setLastError("Failed to send metrics. HTTP response code: " + responseCode);
            throw new RuntimeException("Failed to send metrics. HTTP response code: " + responseCode);
        }
    }
    private static void trustAllCertificates() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
        };

        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    }

    private Map<String, Map<String, String>> extractStageEnvVarsFromLogs(Run<?, ?> run) {
        Map<String, Map<String, String>> stageEnvVars = new HashMap<>();

        try {
            List<String> logs = run.getLog(10000); // limit as needed
            String[] lines = logs.toArray(new String[0]);
            String currentStage = null;

            for (String line : lines) {
                // Detect stage boundaries
                if (line.contains("[Pipeline] {") && currentStage != null) {
                    currentStage = null; // End of stage
                } else if (line.contains("[Pipeline] stage") && line.contains("Entering stage")) {
                    // Example: [Pipeline] stage (Entering stage: Build)
                    int idx = line.indexOf("Entering stage: ");
                    if (idx != -1) {
                        currentStage = line.substring(idx + "Entering stage: ".length()).trim().replaceAll("[)\"]", "");
                        stageEnvVars.putIfAbsent(currentStage, new HashMap<>());
                    }
                }

                // Match variable declaration in log like: BUILD_TOOL=gradle
                if (currentStage != null && line.matches("^[A-Z_]+=.*$")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        stageEnvVars.get(currentStage).put(parts[0], parts[1]);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return stageEnvVars;
    }




}
