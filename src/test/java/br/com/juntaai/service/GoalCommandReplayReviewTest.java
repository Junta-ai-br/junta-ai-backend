package br.com.juntaai.service;

import br.com.juntaai.dto.goal.*;
import br.com.juntaai.exception.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GoalCommandReplayReviewTest {
    @Test void committedReceiptBypassesNewWriteValidationAndBindsOperationAndBody() throws Exception {
        var domain=mock(GoalService.class); var jdbc=mock(JdbcTemplate.class);
        var mapper=new ObjectMapper().findAndRegisterModules();
        UUID user=UUID.randomUUID(), key=UUID.randomUUID(), id=UUID.randomUUID();
        LocalDate past=LocalDate.now().minusDays(1);
        var response=new GoalResponse(id,"Viagem",new BigDecimal("100.00"),BigDecimal.ZERO,past,"IN_PROGRESS");
        var request=new AtomicReference<String>();
        when(jdbc.update(startsWith("INSERT"),eq(user),eq(key),anyString())).thenAnswer(call -> {
            request.compareAndSet(null,call.getArgument(3)); return 0;
        });
        when(jdbc.queryForMap(anyString(),eq(user),eq(key))).thenAnswer(call -> Map.of(
                "request_json",request.get(),"response_json",mapper.writeValueAsString(response)));
        var service=new GoalCommandService(domain,jdbc,mapper);
        assertThat(service.create(user,key,new GoalRequest("Viagem",new BigDecimal("100.00"),past))).isEqualTo(response);
        assertThatThrownBy(() -> service.create(user,key,new GoalRequest("Outra",new BigDecimal("100.00"),past))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.progress(user,key,id,BigDecimal.ONE)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.delete(user,key,id)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(domain); // No new write, even though this deadline is now past.
    }
}
