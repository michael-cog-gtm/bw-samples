package demo.credit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;

@Profile("!standin")
@SpringBootApplication
public class CreditApplication {
    public static void main(String[] args) { SpringApplication.run(CreditApplication.class, args); }
}
