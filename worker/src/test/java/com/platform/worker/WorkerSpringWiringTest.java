package com.platform.worker;

import com.platform.worker.config.RabbitMQConfig;
import com.platform.worker.consumer.JobConsumer;
import com.platform.worker.persistence.TestResultRepository;
import com.platform.worker.persistence.TestResultStore;
import com.platform.worker.runner.RequestUriBuilder;
import com.platform.worker.runner.WebClientHttpRunner;
import com.platform.worker.service.JobExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = WorkerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
        }
)
class WorkerSpringWiringTest {

    @MockBean
    private TestResultRepository testResultRepository;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsAndWiresAllWorkerBeans() {
        assertNotNull(context.getBean(RabbitMQConfig.class));
        assertNotNull(context.getBean(WebClientHttpRunner.class));
        assertNotNull(context.getBean(RequestUriBuilder.class));
        assertNotNull(context.getBean(TestResultStore.class));
        assertNotNull(context.getBean(JobExecutionService.class));
        assertNotNull(context.getBean(JobConsumer.class));
    }
}
