package io.jenkins.plugins.sample;

import hudson.Extension;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.*;
import java.util.logging.Logger;

@Extension
public class StageMetricsFlowListener extends FlowExecutionListener {
    private static final Logger LOGGER = Logger.getLogger(StageMetricsFlowListener.class.getName());

    @Override
    public void onCompleted(FlowExecution execution) {
        try {
            WorkflowRun run = (WorkflowRun) execution.getOwner().getExecutable();

            List<Map<String, Object>> stageData = new ArrayList<>();
            List<FlowNode> allNodes = new DepthFirstScanner().allNodes(execution);

            for (FlowNode node : allNodes) {
                LabelAction label = node.getAction(LabelAction.class);
                TimingAction timing = node.getAction(TimingAction.class);

                if (label != null && timing != null) {
                    long startTime = TimingAction.getStartTime(node);
                    long duration = System.currentTimeMillis() - startTime;

                    Map<String, Object> stage = new HashMap<>();
                    stage.put("name", node.getDisplayName());
                    stage.put("startTimeMillis", startTime);
                    stage.put("durationMillis", duration);

                    stageData.add(stage);
                }
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("jobName", run.getParent().getFullName());
            payload.put("runId", run.getId());
            payload.put("stages", stageData);

            LOGGER.info("Collected stage metrics: " + payload);
            // Optional: sendMetrics(payload); ← your HTTP sender method here
        } catch (Exception e) {
            LOGGER.warning("Stage metrics collection failed: " + e.getMessage());
        }
    }
}
