package com.sky.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "sky_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDocument {

    @Id
    private Long id;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Keyword)
    private String number;

    @Field(type = FieldType.Integer)
    private Integer status;

    @Field(type = FieldType.Integer)
    private Integer payStatus;

    @Field(type = FieldType.Keyword)
    private String phone;

    @Field(type = FieldType.Text)
    private String consignee;

    @Field(type = FieldType.Text)
    private String address;

    @Field(type = FieldType.Double)
    private Double amount;

    /** 下单时间，存为 epoch millis，便于范围查询 */
    @Field(type = FieldType.Long)
    private Long orderTimeMillis;

    @Field(type = FieldType.Text)
    private String cancelReason;

    /** 菜品摘要，格式："宫保鸡丁*2;;鱼香肉丝*1;" */
    @Field(type = FieldType.Text)
    private String orderDishes;
}
