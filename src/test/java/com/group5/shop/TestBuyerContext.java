package com.group5.shop;

import com.group5.shop.portal.contract.BuyerContext;
import com.group5.shop.portal.contract.BuyerProfile;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用的买家登录态替身（T2-16 / T2-17）。
 *
 * <p><b>为什么需要它</b>：买家登录（注册、会话、buyer 表）归账号与客户模块（T2-02 / T2-03），
 * 在它合入前门户无法真实登录。但「未登录拦不拦、登录后快照对不对、换号串不串数据」
 * 这些正是门户自己的逻辑，不能因为队友没交付就不测。</p>
 *
 * <p>这里用 {@code @Primary} 替身顶掉 {@code ContractDefaults} 里的临时实现：
 * 测试关心的是<b>门户拿到买家身份之后的行为</b>，身份从哪来不是门户的职责。</p>
 *
 * <p>把 {@link #currentId} 置为 {@code null} 即可模拟「未登录」。</p>
 *
 * @author 周到
 */
@TestConfiguration
public class TestBuyerContext {

    /** 默认测试买家 id（真实库里没有这一行，intent.buyer_id 是逻辑外键，不需要外键约束） */
    public static final Long BUYER_ID = 9001L;

    /** 另一个买家，用于验证「换号看不到别人的订单」 */
    public static final Long OTHER_BUYER_ID = 9002L;

    /** 当前登录买家；测试可在用例里临时改成 null 或其他 id */
    public static Long currentId = BUYER_ID;

    /** 恢复默认登录态，避免用例之间互相污染 */
    public static void reset() {
        currentId = BUYER_ID;
    }

    @Bean
    @Primary
    public BuyerContext testBuyerContext() {
        return new BuyerContext() {
            @Override
            public Long currentId() {
                return currentId;
            }

            @Override
            public BuyerProfile profile(Long buyerId) {
                if (buyerId == null) {
                    return null;
                }
                if (buyerId.equals(OTHER_BUYER_ID)) {
                    return new BuyerProfile(OTHER_BUYER_ID, "另一位客户", "13900000000", "东门快递点");
                }
                return new BuyerProfile(buyerId, "测试客户", "13800000000", "图书馆门口");
            }
        };
    }
}
