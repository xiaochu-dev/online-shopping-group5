package com.group5.shop.buyer.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.group5.shop.auth.security.PasswordHasher;
import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.common.api.PageResult;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 买家账号服务（T2-02 / T2-03 / T2-04，挑战需求 A.2.1 + A.2.3）。
 *
 * <p>分层职责与卖家模块一致：本类只做业务规则，不碰 HttpServletRequest / cookie，
 * 「登录成功后怎么发 cookie」是 Controller 的事。</p>
 *
 * <p><b>密码算法</b>：直接复用卖家的 {@link PasswordHasher}（sha256 + 8 字节盐），
 * 澄清结论明确「不引入 BCrypt」。这样两个账号体系的密码算法一致，
 * 将来若整体升级算法，只需换一个实现类。</p>
 *
 * @author 胡成溯（组长）
 */
@Service
public class BuyerAuthService {

    private static final Logger log = LoggerFactory.getLogger(BuyerAuthService.class);

    /** 密码最小长度，与卖家账号保持一致 */
    public static final int MIN_PASSWORD_LENGTH = 8;

    /** 用户名长度区间 */
    private static final int USERNAME_MIN = 2;
    private static final int USERNAME_MAX = 20;

    /** 电话长度上限（不做格式强校验，只防空、防超长） */
    private static final int PHONE_MAX = 20;

    /** 默认交易地点长度上限 */
    private static final int PLACE_MAX = 100;

    private final BuyerMapper buyerMapper;
    private final PasswordHasher passwordHasher;

    public BuyerAuthService(BuyerMapper buyerMapper, PasswordHasher passwordHasher) {
        this.buyerMapper = buyerMapper;
        this.passwordHasher = passwordHasher;
    }

    /**
     * 客户注册（A.2.1）。
     *
     * <p>校验顺序：用户名 → 查重 → 密码 → 电话 → 默认地点。
     * 顺序有意义：先拦掉最基础的格式问题，再做一次数据库查询，
     * 避免为一个明显非法的用户名白跑一趟查重。</p>
     *
     * @param username     用户名
     * @param password     明文密码
     * @param phone        联系电话（必填、不唯一）
     * @param defaultPlace 默认交易地点（选填）
     * @return 新注册的买家
     * @throws BusinessException 校验不通过或用户名已被占用
     */
    @Transactional(rollbackFor = Exception.class)
    public Buyer register(String username, String password, String phone, String defaultPlace) {
        String name = trim(username);
        if (name.length() < USERNAME_MIN || name.length() > USERNAME_MAX) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "用户名长度需在 " + USERNAME_MIN + "~" + USERNAME_MAX + " 个字符之间");
        }
        if (buyerMapper.countByUsername(name) > 0) {
            // 注册场景可以明确告知「用户名已存在」：UI 上本来就允许试错，
            // 这与登录接口刻意模糊「账号不存在/密码错误」的策略不同
            throw new BusinessException(ErrorCode.PARAM_INVALID, "用户名已被注册，请换一个");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_SHORT);
        }
        String tel = trim(phone);
        if (tel.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写联系电话");
        }
        if (tel.length() > PHONE_MAX) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "联系电话过长");
        }
        String place = trim(defaultPlace);
        if (place.length() > PLACE_MAX) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "默认交易地点不得超过 " + PLACE_MAX + " 个字符");
        }

        String salt = passwordHasher.newSalt();
        Buyer buyer = new Buyer();
        buyer.setUsername(name);
        buyer.setSalt(salt);
        buyer.setPasswordHash(passwordHasher.hash(password, salt));
        buyer.setPhone(tel);
        buyer.setDefaultPlace(place.isEmpty() ? null : place);
        buyer.setCreatedAt(TimeUtils.nowMillis());
        buyerMapper.insert(buyer);

        log.info("客户注册成功，用户名={}", name);
        return buyer;
    }

    /**
     * 客户登录（A.2.1）。
     *
     * <p>与卖家登录同一策略：账号不存在与密码错误返回<b>同一句提示</b>，
     * 防止用户名枚举。</p>
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 校验通过的买家
     * @throws BusinessException 用户名或密码错误
     */
    public Buyer login(String username, String password) {
        String name = trim(username);
        if (name.isEmpty() || password == null || password.isEmpty()) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        Buyer buyer = buyerMapper.selectByUsername(name);
        if (buyer == null || !passwordHasher.matches(password, buyer.getSalt(), buyer.getPasswordHash())) {
            log.warn("客户登录失败，用户名={}", name);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        log.info("客户登录成功，用户名={}", name);
        return buyer;
    }

    /**
     * 按 id 查客户（客户详情页用）。
     *
     * @param id 买家 id
     * @return 买家；不存在返回 null
     */
    public Buyer findById(Long id) {
        return id == null ? null : buyerMapper.selectById(id);
    }

    /**
     * 卖家后台「客户管理」分页列表（T2-04，A.2.3）。
     *
     * <p>检索口径：用户名<b>或</b>电话模糊匹配；排序固定为注册时间倒序
     * （新注册的客户排前面，符合运营习惯）。</p>
     *
     * @param keyword  检索词，空则不过滤
     * @param page     页码，从 1 开始
     * @param size     每页条数
     * @return 分页结果
     */
    public PageResult<Buyer> pageForAdmin(String keyword, long page, long size) {
        String kw = trim(keyword);
        Page<Buyer> query = new Page<>(Math.max(page, 1), Math.max(size, 1));
        var wrapper = Wrappers.<Buyer>lambdaQuery()
                .orderByDesc(Buyer::getCreatedAt)
                .orderByDesc(Buyer::getId);
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(Buyer::getUsername, kw).or().like(Buyer::getPhone, kw));
        }
        return PageResult.of(buyerMapper.selectPage(query, wrapper));
    }

    /**
     * 注册客户总数（客户列表页统计卡片）。
     *
     * @return 总数
     */
    public long countAll() {
        return buyerMapper.countAll();
    }

    /** null 安全的去空格 */
    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
