package com.platform.worker.consumer;

import com.platform.worker.model.TestExecutionJob;
import com.platform.worker.service.JobExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class JobConsumer {
    private static final Logger log = LoggerFactory.getLogger(JobConsumer.class);

    private final JobExecutionService jobExecutionService;

    public JobConsumer(JobExecutionService jobExecutionService) {
        this.jobExecutionService = Objects.requireNonNull(jobExecutionService, "JobExecutionService is required");
    }

    @RabbitListener(queues = "${app.rabbitmq.queue.execution:test.execution.queue}")
    public List<UUID> handleTestExecutionJob(TestExecutionJob job) {
        if (job == null) {
            log.error("Received null TestExecutionJob payload");
            throw new AmqpRejectAndDontRequeueException("Payload is null");
        }

        try {
            log.info("Received TestExecutionJob: jobId={}, testRunId={}, caseCount={}",
                    job.jobId(), job.testRunId(), job.testCases().size());
            List<UUID> resultIds = jobExecutionService.executeJob(job);
            log.info("Completed TestExecutionJob: jobId={}, savedResults={}", job.jobId(), resultIds.size());
            return resultIds;
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("Invalid job payload or unrecoverable state: {}", e.getMessage());
            throw new AmqpRejectAndDontRequeueException("Invalid job payload: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Transient error executing job: {}", e.getMessage(), e);
            throw e;
        }
    }
}
