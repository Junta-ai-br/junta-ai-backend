package br.com.juntaai.entity;

import br.com.juntaai.dto.planner.SimulationRequest.HistoryContext;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;

@Entity
@Table(name = "planner_simulations")
@Getter
@Setter
public class PlannerSimulation extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "goal_name", nullable = false, length = 150)
    private String goalName;
    @Column(name = "goal_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal goalAmount;
    @Column(name = "deadline_months", nullable = false)
    private Integer deadlineMonths;
    @Column(name = "analysis_base", nullable = false, length = 20)
    private String analysisBase;
    @Column(name = "monthly_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyAmount;
    @Column(name = "estimated_months", nullable = false)
    private Integer estimatedMonths;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "history_context", columnDefinition = "jsonb")
    private HistoryContext historyContext;
}
