package com.sky.benchmark;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Aspect
@Component
@ConditionalOnProperty(name="sky.paid-coupon.benchmark-enabled", havingValue="true")
public class BenchmarkSqlObservation {
    @Around("execution(* com.sky.mapper.PaidCoupon*Mapper.*(..))")
    public Object observe(ProceedingJoinPoint call) throws Throwable {
        long start=System.nanoTime();
        try { return call.proceed(); }
        finally { ConnectionMetrics.record("mapper."+call.getSignature().getName(),System.nanoTime()-start); }
    }
}
