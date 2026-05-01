package emailworkflow;

import java.time.Duration;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

public class EmailWorkflowImpl implements EmailWorkflow {

    private boolean cancelled = false;

    /*
     * Activity stub with retry policy:
     *   - Up to 3 attempts
     *   - 2-second initial backoff, doubling each retry
     *   - 10-second activity timeout per attempt
     */
    private final EmailActivities activities = Workflow.newActivityStub(
            EmailActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(
                            RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)
                                    .setInitialInterval(Duration.ofSeconds(2))
                                    .setBackoffCoefficient(2.0)
                                    .build())
                    .build());

    @Override
    public String run(String to, String subject, String body) {
        // 1. Sleep for 1 minute (durable timer — survives worker restarts)
        Workflow.sleep(Duration.ofMinutes(1));

        // 2. Check if a cancel signal arrived during the sleep
        if (cancelled) {
            return "Email to " + to + " was cancelled before sending.";
        }

        // 3. Send the email (retries automatically on failure per RetryOptions)
        return activities.sendEmail(to, subject, body);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }
}
