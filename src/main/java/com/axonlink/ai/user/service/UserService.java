package com.axonlink.ai.user.service;

import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 用户管理业务层。纯基本息维护，无权限控制。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Autowired
    private SysUserDao userDao;

    public Map<String, Object> page(String keyword, Integer status, int page, int size) {
        int offset = Math.max(0, (page - 1) * size);
        List<SysUser> rows = userDao.list(keyword, status, offset, size);
        int total = userDao.count(keyword, status);
        return Map.of("list", rows, "total", total);
    }

    public SysUser findById(long id) {
        return userDao.findById(id);
    }

    public SysUser create(SysUser u) {
        if (u.getUsername() == null || u.getUsername().isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (userDao.findByUsername(u.getUsername()) != null) {
            throw new IllegalArgumentException("用户名已存在，请换一个");
        }
        if (u.getStatus() == null) u.setStatus(1);
        try {
            long id = userDao.insert(u);
            u.setId(id);
            return u;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("用户名已存在，请换一个");
        }
    }

    public SysUser update(SysUser u) {
        if (u.getId() == null) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        userDao.update(u);
        return userDao.findById(u.getId());
    }

    public void updateStatus(long id, int status, long updaterId) {
        userDao.updateStatus(id, status, updaterId);
    }

    public void delete(long id) {
        userDao.deleteById(id);
    }

    /**
     * LDAP 用户首次登录自动同步到 ccbs_ai_sys_user 表（仅 username + realName）。
     * 已存在则跳过。
     *
     * @param username LDAP username（如 c-wangsh8）
     * @param realName 中文真实姓名（如 王山河）
     */
    public boolean syncLdapUserIfFirstLogin(String username, String realName) {
        if (username == null || username.isBlank()) {
            log.warn("[user-sync] 跳过：username 为空");
            return false;
        }
        if (userDao.findByUsername(username) != null) {
            return false;   // 已存在，无需同步
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setRealName(realName);
        u.setStatus(1);
        u.setRemark("LDAP 首次登录自动同步");
        try {
            long id = userDao.insert(u);
            log.info("[user-sync] LDAP 首次登录同步成功 username={} realName={} id={}",
                    username, realName, id);
            return true;
        } catch (DuplicateKeyException e) {
            // 并发场景兜底：另一个线程已经插入了
            log.debug("[user-sync] LDAP 同步并发跳过 username={}", username);
            return false;
        } catch (Exception e) {
            log.error("[user-sync] LDAP 同步失败 username={} : {}", username, e.getMessage(), e);
            return false;
        }
    }
}
