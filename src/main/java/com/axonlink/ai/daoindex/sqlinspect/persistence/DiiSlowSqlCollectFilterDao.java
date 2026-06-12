package com.axonlink.ai.daoindex.sqlinspect.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * {@code dii_slow_sql_collect_filter} 读写 DAO（结果库 MySQL，V23）。
 *
 * <p>慢SQL「采集过滤名单」：抽象SQL 以表中任一前缀开头（大小写不敏感）→ 导入时不纳入采集。
 * 增删受导入口令保护（Controller 层校验）；与审批白名单无关。
 */
@Repository
public class DiiSlowSqlCollectFilterDao {

    private final JdbcTemplate jdbc;

    public DiiSlowSqlCollectFilterDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /** 全部条目（配置弹窗列表用）：{id, prefix, created_at}，按前缀排序。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList(
                "SELECT id, prefix, created_at FROM dii_slow_sql_collect_filter ORDER BY prefix");
    }

    /** 仅前缀清单（导入过滤用，已 trim 入库）。 */
    public List<String> listPrefixes() {
        return jdbc.queryForList(
                "SELECT prefix FROM dii_slow_sql_collect_filter ORDER BY prefix", String.class);
    }

    /**
     * 新增前缀；重复（唯一键冲突）抛 {@link IllegalStateException} 给上层转友好提示。
     */
    public void insert(String prefix) {
        try {
            jdbc.update("INSERT INTO dii_slow_sql_collect_filter (prefix) VALUES (?)", prefix);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("该前缀已存在：" + prefix);
        }
    }

    /** 删除条目，返回删除行数（0=不存在）。 */
    public int deleteById(long id) {
        return jdbc.update("DELETE FROM dii_slow_sql_collect_filter WHERE id = ?", id);
    }
}
