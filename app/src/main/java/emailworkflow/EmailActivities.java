package emailworkflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface EmailActivities {

    @ActivityMethod
    String sendEmail(String to, String subject, String body);
}
