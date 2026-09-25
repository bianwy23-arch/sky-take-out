package com.sky.mapper;

import com.sky.entity.IdSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IdSegmentMapper {

    @Update("update id_segment set max_id = max_id + step, update_time = now() where biz_type = #{bizType}")
    int allocate(@Param("bizType") String bizType);

    @Select("select biz_type, max_id, step, update_time from id_segment where biz_type = #{bizType}")
    IdSegment getByBizType(@Param("bizType") String bizType);
}
