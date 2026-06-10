package com.axonlink.ai.user.controller;

import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.service.UserService;
import com.axonlink.common.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户管理基础接口。纯 CRUD，不做权限控制。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserService userService;

    @GetMapping("/page")
    public R<Map<String, Object>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return R.ok(userService.page(keyword, status, page, size));
        } catch (Exception e) {
            log.error("查询用户列表失败", e);
            return R.fail("查询失败：" + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public R<SysUser> get(@PathVariable long id) {
        try {
            return R.ok(userService.findById(id));
        } catch (Exception e) {
            return R.fail("查询失败：" + e.getMessage());
        }
    }

    @PostMapping
    public R<SysUser> create(@RequestBody SysUser u) {
        try {
            return R.ok(userService.create(u));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("新增用户失败", e);
            return R.fail("新增失败：" + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public R<SysUser> update(@PathVariable long id, @RequestBody SysUser u) {
        try {
            u.setId(id);
            return R.ok(userService.update(u));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("更新用户失败", e);
            return R.fail("更新失败：" + e.getMessage());
        }
    }

    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable long id, @RequestParam int status) {
        try {
            userService.updateStatus(id, status, 0L);
            return R.ok(null);
        } catch (Exception e) {
            return R.fail("操作失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        try {
            userService.delete(id);
            return R.ok(null);
        } catch (Exception e) {
            log.error("删除用户失败", e);
            return R.fail("删除失败：" + e.getMessage());
        }
    }
}
