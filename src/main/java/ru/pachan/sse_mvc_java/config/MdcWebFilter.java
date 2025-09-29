package ru.pachan.sse_mvc_java.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
public class MdcWebFilter extends OncePerRequestFilter {

    private static final String REQUEST_UID_KEY = "requestUid";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String requestUid = UUID.randomUUID().toString();
        MDC.put(REQUEST_UID_KEY, requestUid);
        log.info("Mdc Web Filter");

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_UID_KEY);
        }
    }

}
