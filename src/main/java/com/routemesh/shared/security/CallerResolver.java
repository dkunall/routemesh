package com.routemesh.shared.security;

import com.routemesh.shared.config.RouteMeshProperties;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Turns the bearer token into a {@link Caller}. Dispatcher subjects come from configuration, not from token claims. */
@Component
public class CallerResolver implements HandlerMethodArgumentResolver {

    private final Set<String> dispatchers;

    public CallerResolver(RouteMeshProperties props) {
        this.dispatchers = Set.copyOf(props.security().dispatcherSubjects());
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || jwt.getSubject() == null) {
            throw new IllegalStateException("no authenticated subject on a /v1 request");
        }
        String subject = jwt.getSubject();
        return new Caller(subject, dispatchers.contains(subject) ? Role.DISPATCHER : Role.SHIPPER);
    }
}
