package com.axonlink.ai.user.entity;

import java.util.Date;

/**
 * 系统用户基本信息实体。纯 POJO，参照 CodeFileAuthorStat 风格（不继承 BaseEntity）。
 * 仅做基本息维护，不包含权限相关字段（isAdmin/roleType/leaderXxxId 等）。
 */
public class SysUser {

    private Long id;
    private String username;
    private String realName;
    private String empNo;
    private String email;
    private String phone;
    private String department;
    private Integer status;
    private String remark;
    private Long creatorId;
    private Date createTime;
    private Long updaterId;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = realName; }
    public String getEmpNo() { return empNo; }
    public void setEmpNo(String empNo) { this.empNo = empNo; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Long getUpdaterId() { return updaterId; }
    public void setUpdaterId(Long updaterId) { this.updaterId = updaterId; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
