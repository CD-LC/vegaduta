package com.agenticai.core.common.apicontract;

import com.agenticai.core.security.http.ApiDeprecated;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adds the deprecation headers to every response of a handler marked {@link ApiDeprecated}:
 *
 * <ul>
 *   <li>{@code Deprecation: @<unix seconds>} (RFC 9745), the date the deprecation was announced
 *   <li>{@code Sunset: <HTTP-date>} (RFC 8594), when a sunset date is set
 *   <li>{@code Link: <policy>; rel="deprecation"; type="text/html"} pointing at the policy page
 * </ul>
 *
 * Headers are set in {@code preHandle}, before the body is written, so streaming handlers carry them
 * too.
 */
@Configuration
public class ApiDeprecationInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final String DEPRECATION = "Deprecation";
    static final String SUNSET = "Sunset";
    static final String LINK = "Link";

    /** IMF-fixdate (RFC 9110, section 5.6.7): two-digit day, always GMT. */
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    private final String policyUrl;
    private final Map<Method, Optional<Headers>> cache = new ConcurrentHashMap<>();

    record Headers(String deprecation, String sunset, String link) {}

    public ApiDeprecationInterceptor(
            @Value(
                            "${app.api.deprecation-policy-url:https://github.com/CD-LC/vegaduta/blob/main/docs/api/DEPRECATION.md}")
                    String policyUrl) {
        this.policyUrl = policyUrl;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod hm) {
            cache.computeIfAbsent(hm.getMethod(), m -> headersFor(hm))
                    .ifPresent(
                            h -> {
                                response.setHeader(DEPRECATION, h.deprecation());
                                if (h.sunset() != null) {
                                    response.setHeader(SUNSET, h.sunset());
                                }
                                response.addHeader(LINK, h.link());
                            });
        }
        return true;
    }

    private Optional<Headers> headersFor(HandlerMethod hm) {
        ApiDeprecated a = find(hm.getMethod(), hm.getBeanType());
        if (a == null) {
            return Optional.empty();
        }
        String sunset = a.sunset().isBlank() ? null : httpDate(a.sunset());
        return Optional.of(
                new Headers(
                        "@" + epochSeconds(a.since()),
                        sunset,
                        "<" + policyUrl + ">; rel=\"deprecation\"; type=\"text/html\""));
    }

    /** The method's annotation, else its controller's. */
    public static ApiDeprecated find(Method method, Class<?> controller) {
        ApiDeprecated a = AnnotatedElementUtils.findMergedAnnotation(method, ApiDeprecated.class);
        return a != null
                ? a
                : AnnotatedElementUtils.findMergedAnnotation(controller, ApiDeprecated.class);
    }

    static long epochSeconds(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    static String httpDate(String isoDate) {
        ZonedDateTime t = LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC);
        return HTTP_DATE.format(t);
    }
}
