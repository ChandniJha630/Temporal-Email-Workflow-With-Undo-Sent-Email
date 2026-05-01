package emailworkflow;

public class EmailActivitiesImpl implements EmailActivities {

    @Override
    public String sendEmail(String to, String subject, String body) {
        // Simulate sending an email. Replace with real email logic (SES, SMTP, etc.)
        System.out.printf("Sending email to=%s subject='%s' body='%s'%n", to, subject, body);

        // Simulate occasional failure for demo purposes — remove in production
        // if (Math.random() < 0.3) {
        //     throw new RuntimeException("Transient email service error");
        // }

        return "Email sent successfully to " + to;
    }
}
