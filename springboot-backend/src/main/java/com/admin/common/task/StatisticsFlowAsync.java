package com.admin.common.task;

import com.admin.mapper.StatisticsFlowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
public class StatisticsFlowAsync {
    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final Duration RETENTION = Duration.ofHours(48);

    private final StatisticsFlowMapper mapper;

    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void statistics_flow() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime hour = now.truncatedTo(ChronoUnit.HOURS);
        mapper.deleteBefore(now.toInstant().minus(RETENTION).toEpochMilli());
        mapper.insertHourlySnapshot(HOUR_LABEL.format(hour), now.toInstant().toEpochMilli(),
                hour.toInstant().toEpochMilli(), hour.plusHours(1).toInstant().toEpochMilli());
    }
}
