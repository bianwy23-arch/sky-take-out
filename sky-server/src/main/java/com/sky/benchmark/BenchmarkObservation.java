package com.sky.benchmark;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;

@Component
@ConditionalOnProperty(name="sky.paid-coupon.benchmark-enabled", havingValue="true")
public class BenchmarkObservation extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path=req.getRequestURI();
        String caller=path.endsWith("/claim") ? "claim" : path.endsWith("/release") ? "release" :
                path.equals("/user/paid-coupon/reservations") ? "reserve" : "other";
        ConnectionMetrics.CALLER.set(caller);
        long start=System.nanoTime();
        try { chain.doFilter(req,res); }
        finally { ConnectionMetrics.record("http",System.nanoTime()-start); ConnectionMetrics.CALLER.remove(); }
    }
}
