package br.com.juntaai.service;

import br.com.juntaai.integration.ai.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialPreviewMidnightTest {
    @ParameterizedTest
    @CsvSource({
        "EXPENSE,2020-01-01T23:59:59Z,2020-01-02T00:00:01Z",
        "INCOME,2020-01-01T23:59:59Z,2020-01-02T00:00:01Z",
        "EXPENSE,2020-01-02T02:59:59Z,2020-01-02T03:00:01Z",
        "INCOME,2020-01-02T02:59:59Z,2020-01-02T03:00:01Z"
    })
    void utcAndSaoPauloMidnightDoNotReconstructTheFinancialDate(String kind,String start,String end) {
        ZoneId zone=ZoneId.of("America/Sao_Paulo");
        Clock before=Clock.fixed(Instant.parse(start),zone),after=Clock.fixed(Instant.parse(end),zone);
        LocalDate original=LocalDate.now(before),later=LocalDate.now(after);
        var operation=Operation.valueOf(kind);
        var payload=new ParsedFinancialData(new BigDecimal("45.00"),"synthetic frozen description","Synthetic edge","hoje",
            original.toString(),null,operation,TemporalStatus.REALIZED,"hoje");
        var pending=new PendingOperation(PendingState.CONFIRMATION,operation,payload,List.of("confirmation"),null,
            UUID.randomUUID().toString(),UUID.randomUUID().toString(),null,UUID.randomUUID().toString());
        try(var dates=mockStatic(LocalDate.class,CALLS_REAL_METHODS)) {
            dates.when(()->LocalDate.now(zone)).thenReturn(original);
            String shown=FinancialPreview.render(pending);assertThat(shown).isNotNull();
            dates.when(()->LocalDate.now(zone)).thenReturn(later);
            assertThat(FinancialPreview.render(pending)).isEqualTo(shown);
            assertThat(pending.known_fields()).isEqualTo(payload);
            assertThat(shown).contains("45,00","Synthetic edge","synthetic frozen description",
                original.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")));
        }
    }
}
