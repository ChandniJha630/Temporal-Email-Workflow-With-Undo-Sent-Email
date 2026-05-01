package emailworkflow;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
/**
 * 
 * Worker registers itself to listen on EMAIL_TASK_QUEUE — "I can handle email workflows and activities"
 * Starter submits a workflow to EMAIL_TASK_QUEUE — "Run this workflow on whatever worker is listening here"
 * Temporal server matches them — it puts the task in the queue, and the worker picks it up
 * Starter  ──▶  Temporal Server  ──▶  Worker
         "run on EMAIL_TASK_QUEUE"    (listening on EMAIL_TASK_QUEUE)

 */
public class EmailWorker {

    public static final String TASK_QUEUE = "EMAIL_TASK_QUEUE";

    public static void main(String[] args) {
        // Connect to the local Temporal server
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service);

        // Create a worker that listens on the task queue
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);

        // Register workflow and activity implementations
        worker.registerWorkflowImplementationTypes(EmailWorkflowImpl.class);
        worker.registerActivitiesImplementations(new EmailActivitiesImpl());

        // Start listening for tasks
        factory.start();
        System.out.println("Email worker started. Listening on queue: " + TASK_QUEUE);
    }
}
