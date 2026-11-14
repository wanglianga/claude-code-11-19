package com.bookstore.migration.switchflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 切换失败留痕：独立事务提交。
 * 主流程抛异常回滚业务数据时，FAILED 状态与恢复步骤必须保留，供超时查询看到实际阶段。
 */
@Service
public class SwitchFailureRecorder {

    private final JdbcTemplate jdbc;

    public SwitchFailureRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long requestId, String failReason, String recoveryStep, String actor, String stageNote) {
        jdbc.update("UPDATE target_switch_request SET status='FAILED', fail_reason=?, recovery_step=?, updated_at=? "
                        + "WHERE id=?",
                failReason, recoveryStep, LocalDateTime.now(), requestId);
        jdbc.update("INSERT INTO target_switch_stage_log(id, request_id, stage, actor, note, logged_at) "
                        + "VALUES (nextval('app_global_seq'),?,'TARGET_APPLY_FAILED',?,?,?)",
                requestId, actor, stageNote, LocalDateTime.now());
    }
}
