package demo.credit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;

@Profile("standin")
@SpringBootApplication
public class StandInApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(StandInApplication.class);
        app.setAdditionalProfiles("standin");
        app.run(args);
    }
}
