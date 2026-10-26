package br.com.juntaai.controller;

import br.com.juntaai.dto.goal.GoalResponse;
import br.com.juntaai.security.AuthenticatedUser;
import br.com.juntaai.service.GoalCommandService;
import br.com.juntaai.service.GoalService;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Regression checks for the HTTP contract gaps found during the final review. */
class GoalIdempotencyReviewTest {
    private final GoalCommandService commands = mock(GoalCommandService.class);
    private final AuthenticatedUser user = mock(AuthenticatedUser.class);
    private final UUID owner = UUID.randomUUID();
    private org.springframework.test.web.servlet.MockMvc mvc(LocalValidatorFactoryBean validator) {
        when(user.getId()).thenReturn(owner);
        when(commands.create(any(), any(), any())).thenReturn(new GoalResponse(UUID.randomUUID(), "Viagem",
                new BigDecimal("100.00"), BigDecimal.ZERO, null, "IN_PROGRESS"));
        return MockMvcBuilders.standaloneSetup(new GoalController(mock(GoalService.class), commands))
                .setControllerAdvice(new br.com.juntaai.exception.GlobalExceptionHandler())
                .setValidator(validator).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType() == AuthenticatedUser.class; }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r, WebDataBinderFactory b) { return user; }
                }).build();
    }
    @Test void repeatedHeadersAreRejectedBeforeSelectingAnIdentity() throws Exception {
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet(); var mvc=mvc(validator);
            UUID first=UUID.randomUUID(), second=UUID.randomUUID();
            mvc.perform(post("/goals").header("Idempotency-Key", first, second).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Viagem\",\"targetAmount\":100.00}" )).andExpect(status().isBadRequest());
            verifyNoInteractions(commands);
        }
    }
    @Test void identicalRetryAfterMidnightCanReachReceiptLookup() throws Exception {
        AtomicReference<Instant> instant=new AtomicReference<>(Instant.parse("2026-10-26T12:00:00Z"));
        Clock clock=new Clock() {
            public ZoneId getZone(){ return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone){ return this; }
            public Instant instant(){ return instant.get(); }
        };
        try(var validator=new LocalValidatorFactoryBean()) {
            validator.setConfigurationInitializer(config -> config.clockProvider(() -> clock));
            validator.afterPropertiesSet(); var mvc=mvc(validator); UUID key=UUID.randomUUID();
            String body="{\"name\":\"Viagem\",\"targetAmount\":100.00,\"deadline\":\"2026-10-26\"}";
            mvc.perform(post("/goals").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            instant.set(Instant.parse("2026-10-27T12:00:00Z"));
            mvc.perform(post("/goals").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            verify(commands,times(2)).create(eq(owner),eq(key),any());
        }
    }
    @Test void malformedOrMissingIdentityDoesNotReachTheCommandService() throws Exception {
        try(var validator=new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet(); var mvc=mvc(validator);
            for(String key:new String[]{"", "not-a-uuid"}) mvc.perform(post("/goals").header("Idempotency-Key",key)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Viagem\",\"targetAmount\":100}")).andExpect(status().isBadRequest());
            mvc.perform(post("/goals").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Viagem\",\"targetAmount\":100}")).andExpect(status().isBadRequest());
            verifyNoInteractions(commands);
        }
    }
}
