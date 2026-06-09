package com.axonlink.ai.user.persistence;

import com.axonlink.ai.user.entity.SysUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 用户基本信息 CRUD。仿 CodeRepoConfigDao 的 JdbcTemplate + @Repository 模式。
 * 表名：ccbs_ai_sys_user（ccbs-ai 模块新增，区别于项目存量表）。
 */
@Repository
public class SysUserDao {

    private static final String TBL = "ccbs_ai_sys_user";

    private static final String SELECT_COLS =
            "id, username, real_name, emp_no, email, phone, department, status, " +
            "remark, creator_id, create_time, updater_id, update_time";

    private final JdbcTemplate jdbc;

    public SysUserDao(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    private static final RowMapper<SysUser> MAPPER = (rs, n) -> {
        SysUser u = new SysUser();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setRealName(rs.getString("real_name"));
        u.setEmpNo(rs.getString("emp_no"));
        u.setEmail(rs.getString("email"));
        u.setPhone(rs.getString("phone"));
        u.setDepartment(rs.getString("department"));
        u.setStatus(rs.getInt("status"));
        u.setRemark(rs.getString("remark"));
        u.setCreatorId(rs.getObject("creator_id") == null ? null : rs.getLong("creator_id"));
        u.setCreateTime(rs.getTimestamp("create_time"));
        u.setUpdaterId(rs.getObject("updater_id") == null ? null : rs.getLong("updater_id"));
        u.setUpdateTime(rs.getTimestamp("update_time"));
        return u;
    };

    /** 分页查询（关键字+状态）。 */
    public List<SysUser> list(String keyword, Integer status, int offset, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLS + " FROM " + TBL + " WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username LIKE ? OR real_name LIKE ? OR emp_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 总数（与 list 同条件）。 */
    public int count(String keyword, Integer status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM " + TBL + " WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (username LIKE ? OR real_name LIKE ? OR emp_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        return jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
    }

    public SysUser findById(long id) {
        return jdbc.queryForObject("SELECT " + SELECT_COLS + " FROM " + TBL + " WHERE id = ?",
                MAPPER, id);
    }

    public SysUser findByUsername(String username) {
        return jdbc.query("SELECT " + SELECT_COLS + " FROM " + TBL + " WHERE username = ? LIMIT 1",
                MAPPER, username).stream().findFirst().orElse(null);
    }

    public SysUser findByEmpNo(String empNo) {
        if (empNo == null || empNo.isBlank()) return null;
        return jdbc.query("SELECT " + SELECT_COLS + " FROM " + TBL + " WHERE emp_no = ? LIMIT 1",
                MAPPER, empNo.trim()).stream().findFirst().orElse(null);
    }

    public long insert(SysUser u) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO " + TBL + " (username, real_name, emp_no, email, phone, " +
                "department, status, remark, creator_id, create_time, updater_id, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?, NOW())",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, u.getUsername());
            ps.setString(2, u.getRealName());
            ps.setString(3, u.getEmpNo());
            ps.setString(4, u.getEmail());
            ps.setString(5, u.getPhone());
            ps.setString(6, u.getDepartment());
            ps.setInt(7, u.getStatus() == null ? 1 : u.getStatus());
            ps.setString(8, u.getRemark());
            ps.setObject(9, u.getCreatorId());
            ps.setObject(10, u.getUpdaterId());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? 0L : key.longValue();
    }

    public int update(SysUser u) {
        return jdbc.update(
            "UPDATE " + TBL + " SET real_name=?, emp_no=?, email=?, phone=?, " +
            "department=?, status=?, remark=?, updater_id=?, update_time=NOW() WHERE id=?",
            u.getRealName(), u.getEmpNo(), u.getEmail(), u.getPhone(),
            u.getDepartment(), u.getStatus(), u.getRemark(), u.getUpdaterId(), u.getId());
    }

    public int updateStatus(long id, int status, long updaterId) {
        return jdbc.update(
            "UPDATE " + TBL + " SET status=?, updater_id=?, update_time=NOW() WHERE id=?",
            status, updaterId, id);
    }

    public int deleteById(long id) {
        return jdbc.update("DELETE FROM " + TBL + " WHERE id = ?", id);
    }
}
