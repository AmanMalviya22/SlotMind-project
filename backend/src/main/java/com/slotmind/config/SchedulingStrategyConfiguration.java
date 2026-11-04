package com.slotmind.config;

import com.slotmind.strategy.AISchedulingStrategy;
import com.slotmind.strategy.SchedulingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SchedulingStrategyConfiguration {

    /**
     * AI is always the first recommendation strategy.
     * AISchedulingStrategy delegates to the deterministic rule-based strategy
     * whenever the gateway is unavailable or its recommendation cannot be used.
     */
    @Bean
    @Primary
    public SchedulingStrategy selectedSchedulingStrategy(
            AISchedulingStrategy aiSchedulingStrategy) {
        return aiSchedulingStrategy;
    }
}
