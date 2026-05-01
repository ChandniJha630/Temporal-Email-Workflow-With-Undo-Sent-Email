package emailworkflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface EmailWorkflow {

    @WorkflowMethod
    String run(String to, String subject, String body);

    @SignalMethod
    void cancel();
}
