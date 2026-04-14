package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.document.DishDocument;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Category;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DishServiceImpl implements DishService {
    /**
     * 新增菜品，同时保存对应的口味数据
     *
     * @param dishDTO
     */

    private static final String ES_DISH_INDEX = "sky_dishes";

    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private DishFlavorMapper dishFlavorMapper;
    @Autowired
    private SetmealDishMapper setmealDishMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ElasticsearchOperations esOperations;

    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //向菜品表插入一条数据
        dishMapper.insert(dish);
        //获取insert语句生成的主键值
        Long dishId = dish.getId();
        //向口味表插入n条数据
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishId);
            });
            dishFlavorMapper.insertBatch(flavors);
        }

        // 同步到 ES
        saveOrUpdateDishInEs(dish);
    }

    @Override
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());

    }
    /**
     * 批量删除功能
     * @param ids
     */
    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        //判断当前菜品是否能够删除--是否存在起售中的菜品？
        for (Long id : ids){
            Dish dish =dishMapper.getById(id);
            if(dish.getStatus()== StatusConstant.ENABLE){
                //当前菜品处于起售中，不能删除
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }
        //判断是否能够删除--当前菜品是否被套餐关联了？
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if(setmealIds!=null && setmealIds.size()>0){
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }
        //删除菜品表中的菜品数据
        for (Long id : ids){
            dishMapper.deleteById(id);
            //删除菜品关联的口味数据
            dishFlavorMapper.deleteByDishId(id);
        }
//delete from dish where id in(?,?,?)
        //根据菜品id集合，批量删除菜品数据
        dishMapper.deleteByIds(ids);
        //根据菜品id集合，删除菜品口味数据
        dishFlavorMapper.deleteByDishIds(ids);

        // 从 ES 删除
        for (Long id : ids) {
            deleteDishFromEs(id);
        }
    }

    @Override
    public DishVO getByIdWithFlavor(Long id) {
        //根据id查询菜品数据
        Dish dish = dishMapper.getById(id);
        //根据id查口味数据
        List<DishFlavor> dishFlavors=dishFlavorMapper.getByDishId(id);

        //将查询数据封装到VO
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish,dishVO);
        dishVO.setFlavors(dishFlavors);
        return dishVO;
    }

    @Override
    public void updateWithFlavor(DishDTO dishDTO) {
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        //修改菜品
        dishMapper.update(dish);
        //删除原有口味数据
        dishFlavorMapper.deleteByDishId(dishDTO.getId());
        //重新插入口味数据
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if(flavors != null && flavors.size() > 0){
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dishDTO.getId());
            });
            dishFlavorMapper.insertBatch(flavors);
        }

        // 同步到 ES（upsert）
        Dish updated = dishMapper.getById(dishDTO.getId());
        if (updated != null) {
            saveOrUpdateDishInEs(updated);
        }
    }

    @Override
    public List<Dish> list(Long categoryId) {
        com.sky.entity.Dish dish =Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE)
                .build();
        return dishMapper.list(dish);
    }

    /**
     * 根据id查询菜品和对应的口味
     *
     * @param id
     * @return
     */


    /**
     * 条件查询菜品和口味
     * @param dish
     * @return
     */
    public List<DishVO> listWithFlavor(Dish dish) {
        List<Dish> dishList = dishMapper.list(dish);

        List<DishVO> dishVOList = new ArrayList<>();

        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(dish,dishVO);

            //根据菜品id查询对应的口味
            List<DishFlavor> flavors = dishFlavorMapper.getByDishId(d.getId());

            dishVO.setFlavors(flavors);
            dishVOList.add(dishVO);
        }

        return dishVOList;
    }

    @Override
    public void startOrStop(Integer status, Long id) {
        Dish dish = Dish.builder()
                .status(status)
                .id(id)
                .build();
        dishMapper.update(dish);
        updateEsDishStatus(id, status);
    }

    @Override
    public List<DishVO> searchByKeyword(String keyword) {
        org.elasticsearch.index.query.BoolQueryBuilder boolQuery =
                QueryBuilders.boolQuery()
                        .filter(QueryBuilders.termQuery("status", StatusConstant.ENABLE));
        if (keyword != null && !keyword.isEmpty()) {
            boolQuery.must(QueryBuilders.multiMatchQuery(keyword, "name", "description"));
        }

        org.springframework.data.elasticsearch.core.query.NativeSearchQuery query =
                new NativeSearchQueryBuilder()
                        .withQuery(boolQuery)
                        .build();

        org.springframework.data.elasticsearch.core.IndexOperations indexOps =
                esOperations.indexOps(DishDocument.class);
        if (!indexOps.exists()) {
            indexOps.createWithMapping();
            return new ArrayList<>();
        }

        SearchHits<DishDocument> hits = esOperations.search(query, DishDocument.class);
        return hits.getSearchHits().stream()
                .map(hit -> {
                    DishDocument doc = hit.getContent();
                    DishVO vo = new DishVO();
                    vo.setId(doc.getId());
                    vo.setName(doc.getName());
                    vo.setCategoryId(doc.getCategoryId());
                    vo.setCategoryName(doc.getCategoryName());
                    vo.setPrice(doc.getPrice() != null ? BigDecimal.valueOf(doc.getPrice()) : null);
                    vo.setImage(doc.getImage());
                    vo.setDescription(doc.getDescription());
                    vo.setStatus(doc.getStatus());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public int syncAllDishesToEs() {
        List<Dish> allDishes = dishMapper.list(new Dish());
        int count = 0;
        for (Dish dish : allDishes) {
            saveOrUpdateDishInEs(dish);
            count++;
        }
        log.info("批量同步菜品到 ES 完成，共同步 {} 条", count);
        return count;
    }

    // -------- ES helper methods --------

    private void saveOrUpdateDishInEs(Dish dish) {
        try {
            String categoryName = null;
            if (dish.getCategoryId() != null) {
                Category category = categoryMapper.getById(dish.getCategoryId());
                if (category != null) {
                    categoryName = category.getName();
                }
            }
            DishDocument doc = DishDocument.builder()
                    .id(dish.getId())
                    .name(dish.getName())
                    .categoryId(dish.getCategoryId())
                    .categoryName(categoryName)
                    .price(dish.getPrice() != null ? dish.getPrice().doubleValue() : null)
                    .image(dish.getImage())
                    .description(dish.getDescription())
                    .status(dish.getStatus())
                    .build();
            esOperations.save(doc, IndexCoordinates.of(ES_DISH_INDEX));
        } catch (Exception e) {
            log.error("ES dish sync failed, dishId={}", dish.getId(), e);
        }
    }

    private void deleteDishFromEs(Long dishId) {
        try {
            esOperations.delete(String.valueOf(dishId), IndexCoordinates.of(ES_DISH_INDEX));
        } catch (Exception e) {
            log.error("ES dish delete failed, dishId={}", dishId, e);
        }
    }

    private void updateEsDishStatus(Long dishId, Integer status) {
        try {
            Document doc = Document.create();
            doc.put("status", status);
            UpdateQuery updateQuery = UpdateQuery.builder(String.valueOf(dishId))
                    .withDocument(doc)
                    .build();
            esOperations.update(updateQuery, IndexCoordinates.of(ES_DISH_INDEX));
        } catch (Exception e) {
            log.error("ES dish status sync failed, dishId={}, status={}", dishId, status, e);
        }
    }
}
