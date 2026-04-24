package com.sky.controller.admin.notify;

import com.alibaba.fastjson.JSONObject;
import com.sky.mq.LogisticsEventMqPublisher;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/notify/logistics")
@Slf4j
public class LogisticsNotifyController {

    @Autowired
    private LogisticsEventMqPublisher logisticsEventMqPublisher;

    /**
     * 本地联调使用：模拟物流状态通知
     * logisticsStatus: 1-已接单, 2-配送中, 3-已送达, 4-配送失败
     */
    @PostMapping("/status/mock")
    public Result<String> statusMock(String orderNo, Integer logisticsStatus, String eventId) {
        if (!StringUtils.hasText(orderNo) || logisticsStatus == null) {
            return Result.error("orderNo/logisticsStatus 不能为空");
        }
        String finalEventId = StringUtils.hasText(eventId) ? eventId : UUID.randomUUID().toString();
        JSONObject payload = new JSONObject();
        payload.put("eventId", finalEventId);
        payload.put("orderNo", orderNo);
        payload.put("logisticsStatus", logisticsStatus);
        payload.put("eventTime", LocalDateTime.now().toString());

        logisticsEventMqPublisher.publish(payload.toJSONString());
        log.info("mock logistics event published, orderNo={}, logisticsStatus={}, eventId={}",
                orderNo, logisticsStatus, finalEventId);
        return Result.success(finalEventId);
    }
}

