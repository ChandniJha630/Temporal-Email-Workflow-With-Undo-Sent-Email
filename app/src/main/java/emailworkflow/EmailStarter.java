package emailworkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;

public class EmailStarter {

    public static void main(String[] args) {
        // Connect to the local Temporal server
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        // Create a typed workflow stub
        EmailWorkflow workflow = client.newWorkflowStub(
                EmailWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("email-workflow-1")
                        .setTaskQueue(EmailWorker.TASK_QUEUE)
                        .build());

        // Start the workflow asynchronously
        WorkflowClient.start(workflow::run, "user@example.com", "Hello!", "This is the email body.");
        System.out.println("Workflow started. It will sleep 1 minute then send the email.");

        // --- Optional: cancel before the email is sent ---
        // Uncomment the lines below to test the cancel signal:
        //
        System.out.println("Sending cancel signal...");
        workflow.cancel();
        System.out.println("Cancel signal sent.");

        // Wait for the workflow to complete and print the result
        String result = WorkflowStub.fromTyped(workflow).getResult(String.class);
        System.out.println("Workflow result: " + result);
    }
}
