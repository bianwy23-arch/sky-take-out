package com.sky.service.paidcoupon;

import com.sky.dto.PaidCouponItemDTO;
import com.sky.exception.BaseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PaidCouponItems {

    private PaidCouponItems() {
    }

    public static List<PaidCouponItemDTO> normalize(List<PaidCouponItemDTO> items) {
        if (items == null || items.isEmpty()) {
            throw new BaseException("请求参数不完整");
        }
        TreeMap<Long, Integer> merged = new TreeMap<>();
        int totalQuantity = 0;
        for (PaidCouponItemDTO item : items) {
            if (item == null || item.getCouponId() == null) {
                throw new BaseException("请求参数不完整");
            }
            int quantity = item.getQuantity() == null ? 1 : item.getQuantity();
            if (quantity <= 0) {
                throw new BaseException("库存数量必须为正整数");
            }
            if (quantity > 1000 - totalQuantity) {
                throw new BaseException("单次请求总数量不能超过1000份");
            }
            totalQuantity += quantity;
            Integer current = merged.get(item.getCouponId());
            int next = current == null ? quantity : Math.addExact(current, quantity);
            merged.put(item.getCouponId(), next);
        }
        List<PaidCouponItemDTO> normalized = new ArrayList<>(merged.size());
        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            normalized.add(new PaidCouponItemDTO(entry.getKey(), entry.getValue()));
        }
        return normalized;
    }

    public static String canonical(List<PaidCouponItemDTO> normalized) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            PaidCouponItemDTO item = normalized.get(i);
            builder.append("{\"couponId\":")
                    .append(item.getCouponId())
                    .append(",\"quantity\":")
                    .append(item.getQuantity())
                    .append('}');
        }
        builder.append(']');
        return builder.toString();
    }

    public static List<PaidCouponItemDTO> parse(String canonical) {
        if (canonical == null || canonical.length() < 2 || canonical.charAt(0) != '[') {
            throw new BaseException("库存账本不一致");
        }
        if ("[]".equals(canonical)) {
            throw new BaseException("库存账本不一致");
        }
        String body = canonical.substring(1, canonical.length() - 1);
        String[] parts = body.split("\\},\\{");
        List<PaidCouponItemDTO> items = new ArrayList<>(parts.length);
        for (String part : parts) {
            String text = part.replace("{", "").replace("}", "").replace("\"", "");
            String[] fields = text.split(",");
            Long couponId = null;
            Integer quantity = null;
            for (String field : fields) {
                String[] pair = field.split(":");
                if (pair.length != 2) {
                    throw new BaseException("库存账本不一致");
                }
                if ("couponId".equals(pair[0])) {
                    couponId = Long.valueOf(pair[1]);
                } else if ("quantity".equals(pair[0])) {
                    quantity = Integer.valueOf(pair[1]);
                }
            }
            if (couponId == null || quantity == null) {
                throw new BaseException("库存账本不一致");
            }
            items.add(new PaidCouponItemDTO(couponId, quantity));
        }
        return items;
    }
}
