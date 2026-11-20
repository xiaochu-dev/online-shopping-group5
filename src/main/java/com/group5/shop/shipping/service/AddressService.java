package com.group5.shop.shipping.service;

import com.group5.shop.buyer.domain.Buyer;
import com.group5.shop.buyer.domain.BuyerMapper;
import com.group5.shop.common.exception.BusinessException;
import com.group5.shop.common.exception.ErrorCode;
import com.group5.shop.common.util.TimeUtils;
import com.group5.shop.shipping.domain.BuyerAddress;
import com.group5.shop.shipping.domain.BuyerAddressMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址簿（挑战需求 C.2.3，owner：胡成溯）。
 *
 * <p><b>三条业务规则</b>：</p>
 * <ol>
 *   <li><b>首条自动设为默认</b>：新用户不必先点「设为默认」才能下单；</li>
 *   <li><b>默认地址同步回 {@code buyer.default_place}</b>：下单链路原样复用既有字段，
 *       订单域一行代码都不用改；</li>
 *   <li><b>删除默认地址后自动递补</b>：把最近修改的一条设为默认，避免出现「有地址但无默认」，
 *       否则买家下单时会看到交易地点为空。</li>
 * </ol>
 *
 * <p><b>越权防护</b>：所有按 id 的读写都在 SQL 里带 {@code buyer_id}，
 * 影响 0 行即视为「不存在或不是你的」，对外不区分两者。</p>
 *
 * @author 胡成溯（组长）
 */
@Service
public class AddressService {

    /** 收货人姓名上限 */
    private static final int MAX_RECEIVER = 20;
    /** 电话上限（按课程口径只做长度约束，不校验格式） */
    private static final int MAX_PHONE = 20;
    /** 省市区上限 */
    private static final int MAX_REGION = 60;
    /** 详细地址上限 */
    private static final int MAX_DETAIL = 100;

    private final BuyerAddressMapper addressMapper;
    private final BuyerMapper buyerMapper;

    public AddressService(BuyerAddressMapper addressMapper, BuyerMapper buyerMapper) {
        this.addressMapper = addressMapper;
        this.buyerMapper = buyerMapper;
    }

    /**
     * 某买家的地址列表：默认地址排最前。
     *
     * @param buyerId 买家主键
     * @return 地址列表
     */
    public List<BuyerAddress> list(Long buyerId) {
        return addressMapper.selectByBuyer(buyerId);
    }

    /**
     * 新增地址。
     *
     * @param buyerId   买家主键
     * @param receiver  收货人
     * @param phone     电话
     * @param region    省市区
     * @param detail    详细地址
     * @param asDefault 是否设为默认（首条地址无论传什么都会成为默认）
     * @return 新增后的地址
     */
    @Transactional
    public BuyerAddress create(Long buyerId, String receiver, String phone,
                               String region, String detail, boolean asDefault) {
        BuyerAddress address = new BuyerAddress();
        address.setBuyerId(buyerId);
        address.setReceiver(checked(receiver, "收货人", MAX_RECEIVER));
        address.setPhone(checked(phone, "联系电话", MAX_PHONE));
        address.setRegion(checked(region, "省市区", MAX_REGION));
        address.setDetail(checked(detail, "详细地址", MAX_DETAIL));
        long now = TimeUtils.nowMillis();
        address.setCreatedAt(now);
        address.setUpdatedAt(now);

        boolean first = addressMapper.countByBuyer(buyerId) == 0;
        address.setIsDefault(first || asDefault ? 1 : 0);

        if (first || asDefault) {
            addressMapper.clearDefault(buyerId);
        }
        addressMapper.insert(address);
        if (address.defaulted()) {
            syncToBuyer(buyerId, address);
        }
        return address;
    }

    /**
     * 修改地址（不允许改归属，只改内容）。
     *
     * @param buyerId  买家主键
     * @param id       地址主键
     * @param receiver 收货人
     * @param phone    电话
     * @param region   省市区
     * @param detail   详细地址
     * @return 修改后的地址
     */
    @Transactional
    public BuyerAddress update(Long buyerId, Long id, String receiver, String phone,
                               String region, String detail) {
        BuyerAddress address = requireOwned(buyerId, id);
        address.setReceiver(checked(receiver, "收货人", MAX_RECEIVER));
        address.setPhone(checked(phone, "联系电话", MAX_PHONE));
        address.setRegion(checked(region, "省市区", MAX_REGION));
        address.setDetail(checked(detail, "详细地址", MAX_DETAIL));
        address.setUpdatedAt(TimeUtils.nowMillis());
        addressMapper.updateById(address);
        if (address.defaulted()) {
            // 默认地址改了内容，下单用的地点要跟着变
            syncToBuyer(buyerId, address);
        }
        return address;
    }

    /**
     * 删除地址；若删的是默认地址，自动把最近修改的一条递补为默认。
     *
     * @param buyerId 买家主键
     * @param id      地址主键
     */
    @Transactional
    public void delete(Long buyerId, Long id) {
        BuyerAddress address = requireOwned(buyerId, id);
        int rows = addressMapper.deleteOwned(id, buyerId);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        if (address.defaulted()) {
            List<BuyerAddress> rest = addressMapper.selectByBuyer(buyerId);
            if (rest.isEmpty()) {
                syncToBuyer(buyerId, null);
            } else {
                setDefault(buyerId, rest.get(0).getId());
            }
        }
    }

    /**
     * 设为默认地址。
     *
     * @param buyerId 买家主键
     * @param id      地址主键
     */
    @Transactional
    public void setDefault(Long buyerId, Long id) {
        BuyerAddress address = requireOwned(buyerId, id);
        addressMapper.clearDefault(buyerId);
        addressMapper.markDefault(id, buyerId, TimeUtils.nowMillis());
        address.setIsDefault(1);
        syncToBuyer(buyerId, address);
    }

    /**
     * 默认地址文本（下单/页面展示用）；没有地址时返回 null。
     *
     * @param buyerId 买家主键
     * @return 形如「浙江省杭州市西湖区文一西路 18 号」
     */
    public String defaultAddressText(Long buyerId) {
        BuyerAddress address = addressMapper.selectDefault(buyerId);
        return address == null ? null : address.fullAddress();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private BuyerAddress requireOwned(Long buyerId, Long id) {
        BuyerAddress address = addressMapper.selectOwned(id, buyerId);
        if (address == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        return address;
    }

    /**
     * 把默认地址写回 {@code buyer.default_place}，让下单链路无需改动即可使用新地址。
     *
     * @param buyerId 买家主键
     * @param address 默认地址；null 表示清空
     */
    private void syncToBuyer(Long buyerId, BuyerAddress address) {
        Buyer buyer = buyerMapper.selectById(buyerId);
        if (buyer == null) {
            return;
        }
        buyer.setDefaultPlace(address == null ? null : address.fullAddress());
        buyerMapper.updateById(buyer);
    }

    private String checked(String value, String label, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写" + label);
        }
        if (trimmed.length() > max) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, label + "最多 " + max + " 个字符");
        }
        return trimmed;
    }
}
