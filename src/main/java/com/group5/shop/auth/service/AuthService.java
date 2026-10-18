package com.group5.shop.auth.service;

import com.group5.shop.auth.security.PasswordHasher;
import com.group5.shop.common.entity.Seller;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.mapper.SellerMapper;
import com.group5.shop.common.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号服务（T2-05）：登录校验、修改密码。
 *
 * <p>分层职责：本类只做业务规则，不碰 HttpSession、不碰 HttpServletRequest。
 * 「登录成功后怎么写 Session」是 Controller 的事，
 * 这样即便将来要做手机端 API，也能直接复用本类。</p>
 *
 * @author 胡成溯（组长）
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 新密码最小长度（FR-01-5） */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final SellerMapper sellerMapper;
    private final PasswordHasher passwordHasher;

    public AuthService(SellerMapper sellerMapper, PasswordHasher passwordHasher) {
        this.sellerMapper = sellerMapper;
        this.passwordHasher = passwordHasher;
    }

    /**
     * 登录校验（FR-01-1）。
     *
     * <p>安全要点：账号不存在与密码错误<b>返回同一句提示</b>「用户名或密码错误」，
     * 不区分「这个用户名没注册」——否则攻击者可以用报错差异批量探测有效账号（用户名枚举）。</p>
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 校验通过的卖家
     * @throws BusinessException 账号或密码错误时抛出
     */
    public Seller login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        Seller seller = sellerMapper.selectByUsername(username.trim());
        // 无论账号是否存在，都走到同一个异常出口
        if (seller == null || !passwordHasher.matches(password, seller.getSalt(), seller.getPasswordHash())) {
            log.warn("登录失败，用户名={}", username);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        log.info("登录成功，用户名={}", seller.getUsername());
        return seller;
    }

    /**
     * 修改密码（FR-01-4 / FR-01-5）。
     *
     * <p>规则（按顺序校验，先验旧密码再验新密码长度）：
     * <ol>
     *   <li>账号必须存在</li>
     *   <li>原密码必须正确 —— 防止别人趁你电脑没锁屏时偷偷改密</li>
     *   <li>新密码长度 ≥ {@value #MIN_PASSWORD_LENGTH} 位</li>
     *   <li>新密码不能与原密码相同 —— 否则「改密」变成空操作，用户以为改成功了</li>
     *   <li>盐一并轮换后落库</li>
     * </ol>
     * </p>
     *
     * @param username    当前登录用户名（取自 Session，不能由表单传入，否则可越权改他人密码）
     * @param oldPassword 原密码
     * @param newPassword 新密码
     * @throws BusinessException 任一规则不满足时抛出，message 可直接展示给用户
     */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String username, String oldPassword, String newPassword) {
        Seller seller = sellerMapper.selectByUsername(username);
        if (seller == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        if (oldPassword == null || !passwordHasher.matches(oldPassword, seller.getSalt(), seller.getPasswordHash())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_MISMATCH);
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_SHORT);
        }
        if (newPassword.equals(oldPassword)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }

        String newSalt = passwordHasher.newSalt();
        Seller update = new Seller();
        update.setId(seller.getId());
        update.setSalt(newSalt);
        update.setPasswordHash(passwordHasher.hash(newPassword, newSalt));
        sellerMapper.updateById(update);

        log.info("密码修改成功，用户名={}", username);
    }

    /**
     * 按用户名查账号（后台首页展示用）。
     *
     * @param username 用户名
     * @return 账号；不存在返回 null
     */
    public Seller findByUsername(String username) {
        return sellerMapper.selectByUsername(username);
    }

    /** 当前毫秒时间戳，供外部构造审计字段时使用 */
    public long now() {
        return TimeUtils.nowMillis();
    }
}
