package com.group5.shop.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 卖家账号实体，对应表 {@code seller}（T2-06）。
 *
 * <p>注意：{@code passwordHash} 与 {@code salt} 是敏感字段，
 * <b>绝不能</b>直接放进 Model 传给页面；页面只用 {@code username}。</p>
 *
 * @author 胡成溯（组长）
 */
@TableName("seller")
public class Seller {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名，全局唯一 */
    private String username;

    /** sha256(salt + 明文密码) 的十六进制串 */
    private String passwordHash;

    /** 8 字节随机盐的十六进制串 */
    private String salt;

    /** 创建时间，毫秒时间戳 */
    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        // 刻意不输出 passwordHash / salt，避免日志泄露
        return "Seller{id=" + id + ", username='" + username + "'}";
    }
}
