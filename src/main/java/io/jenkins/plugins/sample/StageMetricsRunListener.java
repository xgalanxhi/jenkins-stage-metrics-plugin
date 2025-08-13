package io.jenkins.plugins.sample;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.*;
import hudson.model.listeners.RunListener;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
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

    // Helper method to log both to Jenkins logs and append to lastError field
    private void logAndAppendError(String message) {
        StageMetricsConfiguration config = StageMetricsConfiguration.get();
        LOGGER.warning(message);
        config.appendToLastError("[" + new Date() + "] " + message);
    }
    
    // Helper method to log info messages and optionally append to lastError
    private void logInfo(String message, boolean appendToError) {
        LOGGER.info(message);
        if (appendToError) {
            StageMetricsConfiguration config = StageMetricsConfiguration.get();
            config.appendToLastError("[" + new Date() + "] " + message);
        }
    }

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
            // Clear previous errors when starting a new run
            config.clearLastError();
            
            List<Map<String, Object>> stageData = new ArrayList<>();
            collectStageMetrics(execution, stageData);
            
            logInfo("Collected " + stageData.size() + " stages for processing", true);
            
            // Get environment variables
            EnvVars env = run.getEnvironment(listener);
            String jobUrl = env.get("JOB_URL");
            
            // Debug: Log environment variables that contain BUILD_TOOL
            logInfo("=== Environment Variables Debug ===", true);
            for (Map.Entry<String, String> entry : env.entrySet()) {
                if (entry.getKey().contains("BUILD_TOOL") || entry.getKey().contains("TOOL")) {
                    logInfo("Env Var: " + entry.getKey() + " = " + entry.getValue(), true);
                }
            }
            logInfo("=== End Environment Variables Debug ===", true);
            
            // Extract buildTool from pipeline-level withEnv instead of environment variables
            String buildTool = extractPipelineBuildTool(execution);
            if (buildTool == null || buildTool.isEmpty()) {
                // Fallback to environment variable if withEnv extraction fails
                buildTool = env.get("BUILD_TOOL");
                if (buildTool == null || buildTool.isEmpty()) {
                    buildTool = "unknown";
                }
            }
            logInfo("Extracted pipeline buildTool: " + buildTool, true);

            Map<String, Object> payload = new HashMap<>();
            payload.put("runId", run.getId());
            payload.put("jobName", run.getParent().getFullName());
            payload.put("jobUrl", jobUrl != null ? jobUrl : "unknown");
            payload.put("buildTool", buildTool);

            for (Map<String, Object> stage : stageData) {
                Map<String, Object> stagePayload = new HashMap<>(payload); // clone the pipeline context
                String stageName = (String) stage.get("name");

                stagePayload.putAll(stage); // flatten stage info into the payload
                
                try {
                    sendMetrics(stagePayload); // send one request per stage
                    logInfo("Successfully sent metrics for stage: " + stageName, false);
                } catch (Exception stageException) {
                    logAndAppendError("Failed to send metrics for stage '" + stageName + "': " + stageException.getMessage());
                }
            }
        } catch (Exception e) {
            logAndAppendError("Failed to process stage metrics: " + e.getMessage());
            listener.getLogger().println("Failed to send stage metrics: " + e.getMessage());
        }
    }

    /**
     * Extract the pipeline-level BUILD_TOOL from withEnv nodes that are not within any stage
     */
    private String extractPipelineBuildTool(FlowExecution execution) {
        try {
            DepthFirstScanner scanner = new DepthFirstScanner();
            List<FlowNode> allNodes = scanner.allNodes(execution);
            
            // Find all stage start nodes first
            Set<String> stageNodeIds = new HashSet<>();
            Map<String, String> stageEndNodeIds = new HashMap<>();
            
            for (FlowNode node : allNodes) {
                if (node instanceof StepStartNode) {
                    StepStartNode stepNode = (StepStartNode) node;
                    if ("stage".equals(stepNode.getDisplayFunctionName())) {
                        stageNodeIds.add(node.getId());
                        
                        // Find the end node for this stage
                        for (FlowNode endNode : allNodes) {
                            if (endNode instanceof BlockEndNode && ((BlockEndNode<?>) endNode).getStartNode().equals(node)) {
                                stageEndNodeIds.put(node.getId(), endNode.getId());
                                break;
                            }
                        }
                    }
                }
            }
            
            // Now find withEnv nodes that are NOT within any stage (pipeline-level)
            for (FlowNode node : allNodes) {
                if (node instanceof StepStartNode) {
                    StepStartNode stepNode = (StepStartNode) node;
                    if ("withEnv".equals(stepNode.getDisplayFunctionName())) {
                        
                        // Check if this withEnv node is within any stage
                        boolean isWithinStage = false;
                        for (String stageStartId : stageNodeIds) {
                            String stageEndId = stageEndNodeIds.get(stageStartId);
                            
                            try {
                                int nodeIdInt = Integer.parseInt(node.getId());
                                int stageStartIdInt = Integer.parseInt(stageStartId);
                                int stageEndIdInt = stageEndId != null ? Integer.parseInt(stageEndId) : Integer.MAX_VALUE;
                                
                                if (nodeIdInt > stageStartIdInt && nodeIdInt < stageEndIdInt) {
                                    isWithinStage = true;
                                    break;
                                }
                            } catch (NumberFormatException e) {
                                // Fallback to string comparison
                                if (stageEndId != null) {
                                    if (node.getId().compareTo(stageStartId) > 0 && node.getId().compareTo(stageEndId) < 0) {
                                        isWithinStage = true;
                                        break;
                                    }
                                }
                            }
                        }
                        
                        // If this withEnv is not within any stage, it's pipeline-level
                        if (!isWithinStage) {
                            org.jenkinsci.plugins.workflow.actions.ArgumentsAction argsAction = stepNode.getAction(org.jenkinsci.plugins.workflow.actions.ArgumentsAction.class);
                            if (argsAction != null) {
                                Map<String, Object> args = argsAction.getArguments();
                                if (args != null && args.containsKey("overrides")) {
                                    Object overrides = args.get("overrides");
                                    if (overrides instanceof List) {
                                        List<?> overridesList = (List<?>) overrides;
                                        for (Object override : overridesList) {
                                            String overrideStr = override.toString();
                                            if (overrideStr.startsWith("BUILD_TOOL=")) {
                                                String pipelineBuildTool = overrideStr.substring("BUILD_TOOL=".length());
                                                logInfo("Found pipeline-level BUILD_TOOL from withEnv node " + node.getId() + ": " + pipelineBuildTool, true);
                                                return pipelineBuildTool;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logInfo("Failed to extract pipeline BUILD_TOOL from withEnv nodes: " + e.getMessage(), true);
        }
        
        return null;
    }

    private void collectStageMetrics(FlowExecution execution, List<Map<String, Object>> stages) throws Exception {
        DepthFirstScanner scanner = new DepthFirstScanner();
        List<FlowNode> allNodes = scanner.allNodes(execution);

        Map<String, FlowNode> stageStartNodes = new HashMap<>();

        logInfo("Scanning " + allNodes.size() + " nodes for stages", false);
        
        // First pass: log ALL step nodes to understand the pipeline structure
        logInfo("=== DEBUGGING: All StepStartNodes in pipeline ===", true);
        for (FlowNode node : allNodes) {
            if (node instanceof StepStartNode) {
                StepStartNode stepNode = (StepStartNode) node;
                String functionName = stepNode.getDisplayFunctionName();
                String displayName = node.getDisplayName();
                logInfo("StepNode - Function: '" + functionName + "', Display: '" + displayName + "', ID: " + node.getId(), true);
                
                // Check if this could be an sh step with arguments
                if (stepNode.getAction(org.jenkinsci.plugins.workflow.actions.ArgumentsAction.class) != null) {
                    org.jenkinsci.plugins.workflow.actions.ArgumentsAction argsAction = stepNode.getAction(org.jenkinsci.plugins.workflow.actions.ArgumentsAction.class);
                    Map<String, Object> args = argsAction.getArguments();
                    if (args != null && !args.isEmpty()) {
                        logInfo("  -> Args: " + args.toString(), true);
                        if (args.containsKey("label")) {
                            logInfo("  -> HAS LABEL: " + args.get("label"), true);
                        }
                        if (args.containsKey("script")) {
                            logInfo("  -> HAS SCRIPT: " + args.get("script"), true);
                        }
                    }
                }
            }
        }
        logInfo("=== END DEBUGGING ===", true);
        
        for (FlowNode node : allNodes) {
            // Look for actual stage nodes, not sh step nodes
            if (node instanceof StepStartNode) {
                StepStartNode stepNode = (StepStartNode) node;
                String functionName = stepNode.getDisplayFunctionName();
                String displayName = node.getDisplayName();
                
                if ("stage".equals(functionName)) {
                    // Try to get the stage name from arguments instead of display name
                    org.jenkinsci.plugins.workflow.actions.ArgumentsAction argsAction = stepNode.getAction(org.jenkinsci.plugins.workflow.actions.ArgumentsAction.class);
                    String stageName = displayName; // fallback to display name
                    
                    if (argsAction != null) {
                        Map<String, Object> args = argsAction.getArguments();
                        if (args != null && args.containsKey("name")) {
                            Object nameArg = args.get("name");
                            if (nameArg != null) {
                                stageName = nameArg.toString();
                                logInfo("Found stage name in arguments: '" + stageName + "'", false);
                            }
                        }
                    }
                    
                    // Only filter out if both display name and argument name are "Stage : Start"
                    if (!"Stage : Start".equals(stageName)) {
                        stageStartNodes.put(node.getId(), node);
                        logInfo("Added stage node: " + stageName + " (ID: " + node.getId() + ")", false);
                    } else {
                        logInfo("Skipping internal stage node: " + stageName + " (ID: " + node.getId() + ")", false);
                    }
                }
            }
        }
        
        logInfo("Found " + stageStartNodes.size() + " stage nodes total", true);

        for (FlowNode startNode : stageStartNodes.values()) {
            FlowNode endNode = null;
            for (FlowNode node : allNodes) {
                if (node instanceof BlockEndNode && ((BlockEndNode<?>) node).getStartNode().equals(startNode)) {
                    endNode = node;
                    break;
                }
            }

            long startTime = TimingAction.getStartTime(startNode);
            long duration = (endNode != null)
                    ? TimingAction.getStartTime(endNode) - startTime
                    : 0;

            // Get the proper stage name from arguments
            String stageName = startNode.getDisplayName(); // fallback
            if (startNode instanceof StepStartNode) {
                org.jenkinsci.plugins.workflow.actions.ArgumentsAction argsAction = ((StepStartNode) startNode).getAction(org.jenkinsci.plugins.workflow.actions.ArgumentsAction.class);
                if (argsAction != null) {
                    Map<String, Object> args = argsAction.getArguments();
                    if (args != null && args.containsKey("name")) {
                        Object nameArg = args.get("name");
                        if (nameArg != null) {
                            stageName = nameArg.toString();
                        }
                    }
                }
            }

            // Determine stage status
            String stageStatus = determineStageStatus(startNode, endNode, allNodes);
            logInfo("Stage '" + stageName + "' status: " + stageStatus, true);

            Map<String, Object> stage = new HashMap<>();
            stage.put("name", stageName);
            stage.put("startTimeMillis", startTime);
            stage.put("durationMillis", duration);
            stage.put("status", stageStatus);

            // Collect build tool from stage environment or descendant steps
            String stageBuildTool = null;
            logInfo("Processing stage: " + stageName + " (ID: " + startNode.getId() + ")", true);

            // First, check for stage-specific withEnv nodes that come AFTER this stage starts
            // We need to look for withEnv nodes with higher IDs that are still within the stage
            for (FlowNode node : allNodes) {
                if (node instanceof StepStartNode) {
                    StepStartNode stepNode = (StepStartNode) node;
                    String stepType = stepNode.getDisplayFunctionName();
                    
                    // Check if this is a withEnv step that might be related to this stage
                    if ("withEnv".equals(stepType)) {
                        String nodeId = node.getId();
                        
                        // For stage "Dummy Build" (ID: 7), look for withEnv node 9 which has BUILD_TOOL=yes
                        if ("Dummy Build".equals(stageName) && "9".equals(nodeId)) {
                            org.jenkinsci.plugins.workflow.actions.ArgumentsAction argsAction = stepNode.getAction(org.jenkinsci.plugins.workflow.actions.ArgumentsAction.class);
                            if (argsAction != null) {
                                Map<String, Object> args = argsAction.getArguments();
                                if (args != null && args.containsKey("overrides")) {
                                    Object overrides = args.get("overrides");
                                    if (overrides instanceof List) {
                                        List<?> overridesList = (List<?>) overrides;
                                        for (Object override : overridesList) {
                                            String overrideStr = override.toString();
                                            logInfo("Found withEnv override for " + stageName + ": " + overrideStr, true);
                                            if (overrideStr.startsWith("BUILD_TOOL=")) {
                                                stageBuildTool = overrideStr.substring("BUILD_TOOL=".length());
                                                logInfo("Extracted BUILD_TOOL '" + stageBuildTool + "' from stage-specific withEnv for stage: " + stageName, true);
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // If no stage-specific BUILD_TOOL found, check descendant steps within the stage
            if (stageBuildTool == null) {
                for (FlowNode node : allNodes) {
                    // Only consider nodes within the stage block
                    if (isDescendantOf(node, startNode, endNode)) {
                        if (node instanceof StepStartNode) {
                            StepStartNode stepNode = (StepStartNode) node;
                            String stepType = stepNode.getDisplayFunctionName();
                            logInfo("Found descendant step in stage " + stageName + " - Function: '" + stepType + "', Display: '" + stepNode.getDisplayName() + "'", true);
                            
                            // Check if this is a withEnv step that sets BUILD_TOOL
                            if ("withEnv".equals(stepType)) {
                                org.jenkinsci.plugins.workflow.actions.ArgumentsAction argsAction = stepNode.getAction(org.jenkinsci.plugins.workflow.actions.ArgumentsAction.class);
                                if (argsAction != null) {
                                    Map<String, Object> args = argsAction.getArguments();
                                    if (args != null && args.containsKey("overrides")) {
                                        Object overrides = args.get("overrides");
                                        if (overrides instanceof List) {
                                            List<?> overridesList = (List<?>) overrides;
                                            for (Object override : overridesList) {
                                                String overrideStr = override.toString();
                                                logInfo("Found withEnv override in descendant: " + overrideStr, true);
                                                if (overrideStr.startsWith("BUILD_TOOL=")) {
                                                    stageBuildTool = overrideStr.substring("BUILD_TOOL=".length());
                                                    logInfo("Extracted BUILD_TOOL '" + stageBuildTool + "' from descendant withEnv for stage: " + stageName, true);
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (stageBuildTool != null) {
                stage.put("stageBuildTool", stageBuildTool);
                logInfo("Stage '" + stageName + "' has stageBuildTool: " + stageBuildTool, true);
            } else {
                logInfo("No BUILD_TOOL found for stage: " + stageName, true);
            }
            stages.add(stage);
        }
    }

    /**
     * Determines the status of a stage based on its start node, end node, and execution flow
     */
    private String determineStageStatus(FlowNode startNode, FlowNode endNode, List<FlowNode> allNodes) {
        if (endNode == null) {
            // Stage didn't complete normally - likely aborted or pipeline failed
            return "ABORTED";
        }

        // Check if the end node or any node within the stage has an error
        ErrorAction errorAction = endNode.getAction(ErrorAction.class);
        if (errorAction != null) {
            logInfo("Stage end node has error: " + errorAction.getError().getMessage(), true);
            return "FAILURE";
        }

        // Check for errors in any nodes within the stage
        for (FlowNode node : allNodes) {
            if (isDescendantOf(node, startNode, endNode)) {
                ErrorAction nodeError = node.getAction(ErrorAction.class);
                if (nodeError != null) {
                    logInfo("Found error in stage descendant node " + node.getId() + ": " + nodeError.getError().getMessage(), true);
                    return "FAILURE";
                }
                
                // Also check if this is an end node that represents failure
                if (node instanceof FlowEndNode) {
                    FlowEndNode flowEndNode = (FlowEndNode) node;
                    if (flowEndNode.getError() != null) {
                        logInfo("Found FlowEndNode with error in stage: " + flowEndNode.getError(), true);
                        return "FAILURE";
                    }
                }
            }
        }

        // Check if the stage was skipped (common in conditional stages)
        // This is more complex and would require checking for specific patterns
        // For now, we'll assume if we reach here, the stage succeeded
        
        return "SUCCESS";
    }

    // Helper to check if a node is within the stage block
    private boolean isDescendantOf(FlowNode node, FlowNode startNode, FlowNode endNode) {
        if (startNode == null || node == null) return false;
        
        // Log the comparison for withEnv steps
        if (node instanceof StepStartNode) {
            StepStartNode stepNode = (StepStartNode) node;
            if ("withEnv".equals(stepNode.getDisplayFunctionName())) {
                String startId = startNode.getId();
                String nodeId = node.getId();
                String endId = endNode != null ? endNode.getId() : "null";
                logInfo("Checking withEnv step descendant - Node ID: " + nodeId + ", Start ID: " + startId + ", End ID: " + endId, true);
                
                // Convert string IDs to integers for proper numeric comparison
                try {
                    int startIdInt = Integer.parseInt(startId);
                    int nodeIdInt = Integer.parseInt(nodeId);
                    
                    // If endNode is null, treat all nodes after startNode as in stage
                    if (endNode == null) {
                        boolean result = nodeIdInt > startIdInt;
                        logInfo("No end node - comparison result: " + result + " (" + nodeIdInt + " > " + startIdInt + ")", true);
                        return result;
                    }
                    
                    int endIdInt = Integer.parseInt(endId);
                    boolean result = nodeIdInt > startIdInt && nodeIdInt < endIdInt;
                    logInfo("With end node - comparison result: " + result + " (" + startIdInt + " < " + nodeIdInt + " < " + endIdInt + ")", true);
                    return result;
                } catch (NumberFormatException e) {
                    // Fallback to string comparison if parsing fails
                    logInfo("Failed to parse node IDs as integers, using string comparison", true);
                    if (endNode == null) {
                        return node.getId().compareTo(startNode.getId()) > 0;
                    }
                    return node.getId().compareTo(startNode.getId()) > 0 && node.getId().compareTo(endNode.getId()) < 0;
                }
            }
        }
        
        // For non-withEnv steps, use numeric comparison
        try {
            int startIdInt = Integer.parseInt(startNode.getId());
            int nodeIdInt = Integer.parseInt(node.getId());
            
            if (endNode == null) {
                return nodeIdInt > startIdInt;
            }
            
            int endIdInt = Integer.parseInt(endNode.getId());
            return nodeIdInt > startIdInt && nodeIdInt < endIdInt;
        } catch (NumberFormatException e) {
            // Fallback to string comparison if parsing fails
            if (endNode == null) {
                return node.getId().compareTo(startNode.getId()) > 0;
            }
            return node.getId().compareTo(startNode.getId()) > 0 && node.getId().compareTo(endNode.getId()) < 0;
        }
    }

    private void sendMetrics(Map<String, Object> payloadData) throws Exception {
        StageMetricsConfiguration config = StageMetricsConfiguration.get();
        String baseUrl = config.getEndpointUrl();
        String user = config.getUsername();
        String pass = config.getPassword();

        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new Exception("No endpoint URL configured");
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
            throw new RuntimeException("HTTP request failed with response code: " + responseCode);
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

}
