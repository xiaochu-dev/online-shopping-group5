package com.group5.shop.buyer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 买家账号实体，对应表 {@code buyer}（T2-02，挑战需求 A.2.1）。
 *
 * <p>字段按《挑战需求A-澄清问答记录》定稿，只有五项业务字段：
 * 用户名（唯一）、密码哈希、盐、电话（必填但不唯一）、默认交易地点（选填）。</p>
 *
 * <p><b>不要把 {@code passwordHash} / {@code salt} 放进 Model 传给页面</b>，
 * 卖家后台的客户列表与详情页只展示用户名、电话、默认地点、注册时间。</p>
 *
 * @author 胡成溯（组长）
 */
@TableName("buyer")
public class Buyer {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名，全局唯一 */
    private String username;

    /** sha256(salt + 明文密码) 的十六进制串 */
    private String passwordHash;

    /** 8 字节随机盐的十六进制串，改密时轮换 */
    private String salt;

    /** 联系电话：必填、不做唯一约束、不做格式强校验（澄清结论明确「电话可重复填」） */
    private String phone;

    /** 默认交易地点：选填，下单时可当次改写 */
    private String defaultPlace;

    /** 注册时间，毫秒时间戳 */
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDefaultPlace() {
        return defaultPlace;
    }

    public void setDefaultPlace(String defaultPlace) {
        this.defaultPlace = defaultPlace;
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
        return "Buyer{id=" + id + ", username='" + username + "', phone='" + phone + "'}";
    }
}
